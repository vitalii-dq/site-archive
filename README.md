# Site Archive

A backend service that archives a website from its XML sitemap. You submit a sitemap URL and get a job ID back
immediately. Background workers download the listed HTML pages plus the images, stylesheets, scripts and fonts
they use from the same origin, and package everything into a ZIP.

> [!WARNING]
> **Local use only.** There is no authentication and no SSRF protection: the service fetches any URL it is
> given, including `127.0.0.1`, private networks and cloud metadata addresses, and returns the result in the
> archive. Do not expose it to other users.

Java 25, Spring Boot 4.1, Gradle, PostgreSQL, Liquibase.

## Running

Requires Docker and a JDK 17 or newer to start Gradle. The code needs JDK 25; Gradle downloads it if no JDK 25
is installed.

```bash
./scripts/start-db.sh   # PostgreSQL 17 on localhost:5433 (db/user/password: site_archive)
./gradlew bootRun       # API on http://localhost:8080, archives in ./data/archives
```

Tests (`./gradlew test`) start their own PostgreSQL with Testcontainers, so Docker must be running.

To run the jar instead, pass the same flag as `bootRun`, so that running out of memory stops the service rather
than leaving it half-working (the job is recovered on the next start):

```bash
./gradlew bootJar
java -XX:+ExitOnOutOfMemoryError -jar build/libs/Site-Archive-1.0-SNAPSHOT.jar
```

## Configuration

Settings are in `src/main/resources/application.yml` and can be overridden with environment variables
(e.g. `archive.limits.max-page-size` → `ARCHIVE_LIMITS_MAXPAGESIZE`). The main ones:

| Property                             | Default | Meaning                                  |
|--------------------------------------|---------|------------------------------------------|
| `archive.limits.default-max-pages`   | `100`   | Pages per job when `maxPages` is omitted |
| `archive.limits.max-pages-limit`     | `1000`  | Highest `maxPages` a client may request  |
| `archive.limits.max-page-size`       | `5MB`   | Largest HTML page                        |
| `archive.limits.max-asset-size`      | `25MB`  | Largest asset                            |
| `archive.limits.max-assets-per-job`  | `2000`  | Assets captured per job                  |
| `archive.retry.max-attempts`         | `3`     | Attempts per page, asset and sitemap     |
| `archive.worker.concurrency`         | `2`     | Jobs processed in parallel               |

## API

Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details.

| Method | Path                             | Purpose                                     |
|--------|----------------------------------|---------------------------------------------|
| `POST` | `/api/v1/archives`               | Create a job                                |
| `GET`  | `/api/v1/archives/{id}`          | Status, progress and error summary          |
| `GET`  | `/api/v1/archives`               | All jobs, newest first                      |
| `GET`  | `/api/v1/archives/{id}/pages`    | Outcome of each page                        |
| `GET`  | `/api/v1/archives/{id}/download` | The ZIP (`409` if not finished, `410` if the file is gone) |

List endpoints take `page`, `size` (max `100`) and an optional `status` filter.

```bash
curl -X POST http://localhost:8080/api/v1/archives \
  -H 'Content-Type: application/json' \
  -d '{"sitemapUrl": "https://example.org/sitemap.xml", "maxPages": 50}'
# 202, Location: http://localhost:8080/api/v1/archives/0b7e3c55-...
# {"id":"0b7e3c55-...","status":"QUEUED","createdAt":"..."}

curl http://localhost:8080/api/v1/archives/{id}
curl -OJ http://localhost:8080/api/v1/archives/{id}/download
```

Job status: `QUEUED` → `RUNNING` → `COMPLETED` | `COMPLETED_WITH_ERRORS` (some pages failed) | `FAILED`
(sitemap unusable or no page captured). Jobs interrupted by a shutdown or crash are requeued on the next start.

## What gets archived

- One XML sitemap (`<urlset>`). Sitemap indexes and gzipped sitemaps are rejected.
- Only the first `maxPages` URLs on the sitemap's origin, and only HTML. Links inside pages are not followed.
- Assets referenced from those pages and their stylesheets, if on the same origin. Third-party files (CDNs,
  web fonts) are not downloaded and stay as live links.
- In the ZIP, references to captured assets are rewritten to the local copies. The ZIP also contains an
  `index.html` linking all pages and a `manifest.json` with the outcome of every page and asset.

The result is a static snapshot: links between pages are not rewritten, and files loaded by scripts at runtime
are missing. Serve the unzipped folder with a static web server, as some browsers block fonts and module
scripts opened from disk.

## How it works

A worker takes a job through four stages: read the sitemap, capture each page, capture the assets those pages use,
then write the ZIP. The interesting part is making that survive real web sites and real failures.

**PostgreSQL is the job queue.** There is no broker. A worker claims the oldest queued job with
`SELECT ... FOR UPDATE SKIP LOCKED` and marks it `RUNNING` in the same transaction, so concurrent workers never get
the same job and never wait for each other. A partial index on queued jobs keeps the claim query fast however
much history builds up.

**Short transactions, no I/O inside them.** Every state change (claim, record an attempt, mark a page, complete
the job) is its own small transaction in `ArchiveJobLifecycle`, the only class that writes jobs and pages.
Downloads, backoff and file writes happen between those calls, so a slow site never holds a connection or a lock.
The entities enforce their own state machine, and `@Version` guards against lost updates.

**A job can be stopped at any point and resumed.** The sitemap is read only once; captured pages are skipped on
the next run; each page's attempts are stored, so retries stay bounded across restarts. Every file is downloaded
into a staging directory and moved into place atomically, so a crash never leaves a half-written page, asset or
ZIP. A unique key on (job, URL) makes duplicate pages impossible.
- *Shutdown:* workers are interrupted, and each hands its job back to the queue.
- *Crash:* on start, jobs left `RUNNING` are requeued and count a crash. A job that crashes the service
  `archive.worker.max-crashes-per-job` times (say, by exhausting memory) is failed, not retried forever.
- *Bug:* an unexpected exception fails only that job; the worker moves on.

**Failures are classified, not just caught.** Network errors, timeouts, `429` and `5xx` are retried with
exponential backoff; `4xx`, non-HTML pages, oversized bodies and redirects off the site are permanent and fail at
once. A page failure never fails the job, and an asset failure never fails its page; the job reports
`COMPLETED_WITH_ERRORS` with a reason per page.

**Untrusted input is bounded.** The site being archived controls everything the service reads, so:
- Bodies are streamed to disk, never buffered. A declared `Content-Length` over the limit is refused before
  reading, and an undeclared one is cut off at the limit.
- Redirects are followed by hand, so every hop is checked against the site's origin and the redirect limit.
- The sitemap parser rejects DTDs, which rules out XXE and entity expansion.
- The CSS reference pattern takes linear time even on hostile input (no catastrophic backtracking).
- File names in the archive are generated from IDs and sanitised slugs, never taken from remote paths.

**Links are rewritten so the archive works offline.** Pages are parsed with jsoup. `src`, `srcset`, stylesheet
and icon links, preloads, inline styles and `<style>` blocks are pointed at the local copies. A `<base>` is
resolved and removed, and `integrity` attributes are dropped where a reference became local. Stylesheets are
rewritten as text, so everything except the URLs stays byte-for-byte the same, whatever their encoding. When the
per-job asset limit is hit, stylesheets and fonts are kept ahead of images and scripts, because they matter most
for how a page looks.

**Consistent progress.** A job's status and its page counts are read in one `REPEATABLE READ` transaction, so a
client never sees a sitemap total that disagrees with the page counts. Counts are derived from the page rows,
not kept as counters that could drift.

Also used: Java 25 records, pattern matching for `switch` and virtual-thread workers; Spring Framework 7's
built-in API versioning; Bean Validation with a configurable, Spring-injected constraint; RFC 9457 problem details
for every error.

## How it's tested

`./gradlew test` runs about 250 tests in roughly 15 seconds. They need nothing besides Docker and never contact a real web site.

- **Pure logic:** URL canonicalisation, srcset parsing, CSS and HTML rewriting, file naming and media types, mostly
  as parameterised tests. This includes the linear-time check on hostile CSS.
- **Real HTTP:** `HttpFetcher` runs against an in-process HTTP server, covering redirects, origin checks, size
  limits, timeouts, network errors and interrupts.
- **Real PostgreSQL** (Testcontainers, same image as the dev database): repositories, the job lifecycle and the
  read service. That includes `SKIP LOCKED` across two concurrent transactions and the rule that the claim query
  refuses to run outside a transaction.
- **HTTP contract:** `@WebMvcTest` with the application's own MVC configuration: status codes, the validation
  problem format, API versioning, paging limits and download headers.
- **Worker behaviour:** the processor and the worker pool with collaborators mocked. This covers resuming, attempt
  budgets, cleanup, errors that must not kill a worker, and handing a job back on interrupt.
- **End to end:** the whole application, with real workers, archives a `FakeSite` served on a local port. This
  covers retries, partial failures, staying on the origin, and a shutdown mid-job followed by resuming without
  re-downloading. It also covers crash recovery up to the crash limit, and concurrent workers processing every
  job and page exactly once. The ZIP is unpacked and its rewritten references checked.

Timing is controlled through the injected `Clock` and test properties (millisecond backoffs, short poll intervals),
and asynchronous outcomes are awaited with Awaitility rather than fixed sleeps.

## Limitations

- No SSRF protection or authentication (see the warning above).
- Single instance only: crash recovery assumes no other instance is running.
- No archive cleanup: a finished job keeps its ZIP forever (the pages and assets it was built from are
  deleted once the job completes). No job cancellation, `robots.txt` handling or rate limiting either.
- Lazy-loaded images (`data-src`), SVG `<use>`, `<audio>`/`<video>` media and JavaScript imports are not
  captured.
