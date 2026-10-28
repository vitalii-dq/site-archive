# Site Archive

A backend service that archives a website from its XML sitemap. A client submits a sitemap URL. The service
creates a persistent job and returns immediately. Background workers download the HTML pages listed in
the sitemap, together with the images, stylesheets, scripts and fonts they use from the site's own origin, and
package them into a ZIP archive on the local file system.

> **The output is a static snapshot, not a complete offline replica.** References to captured files are
> rewritten to the copies in the archive; files on other sites (CDNs, web fonts, analytics) are not downloaded
> and are still loaded from those sites. Links between pages are not rewritten, files that scripts load at
> runtime are not captured, and nothing is rendered in a browser. Some browsers block fonts and module scripts
> on pages opened straight from disk; serving the unzipped folder with any static web server avoids that.

> [!WARNING]
> **Local use only.** The service fetches any URL it is given, including loopback, private-network and cloud
> metadata addresses (`127.0.0.1`, `10.0.0.0/8`, `169.254.169.254`, …), and returns what it downloaded in the
> archive. There is also no authentication. Exposed to other users, it is a server-side request forgery (SSRF)
> proxy into its own network. **SSRF protection has to be added before it is deployed anywhere other users can
> reach it**; see [Limitations](#limitations).

Built with Java 25, Spring Boot 4.1 (Spring Framework 7), Gradle, PostgreSQL and Liquibase.

## Running

Requires JDK 25 (Gradle toolchains can provision it) and Docker for the database.

1. Start PostgreSQL. The script runs `postgres:17` in a container named `site-archive-postgres` on
   `localhost:5433`, with database, user and password `site_archive`, matching the defaults in
   `application.yml`. Data is kept in the `site-archive-postgres-data` volume.

   ```bash
   ./scripts/start-db.sh
   ```

2. Run the application. Liquibase creates the schema on startup.

   ```bash
   ./gradlew bootRun
   ```

The API listens on `http://localhost:8080`; change it with `SERVER_PORT`. Archives are written to
`./data/archives`, relative to the working directory (the project folder when using `bootRun`).

Build the executable jar with `./gradlew bootJar` (output: `build/libs/`). Stop the database with
`docker stop site-archive-postgres`.

## Configuration

All settings live in `src/main/resources/application.yml` and can be overridden with environment
variables (Spring Boot relaxed binding, e.g. `archive.limits.max-page-size` → `ARCHIVE_LIMITS_MAXPAGESIZE`).

| Property                                | Default             | Meaning                                                   |
|-----------------------------------------|---------------------|-----------------------------------------------------------|
| `archive.storage.root`                  | `./data/archives`   | Archive directory, relative to the working directory      |
| `archive.limits.default-max-pages`      | `100`               | Page limit used when a request has no `maxPages`          |
| `archive.limits.max-pages-limit`        | `1000`              | Highest `maxPages` a client may request                   |
| `archive.limits.max-sitemap-size`       | `10MB`              | Largest sitemap that will be downloaded                   |
| `archive.limits.max-page-size`          | `5MB`               | Largest HTML page that will be downloaded                 |
| `archive.limits.max-asset-size`         | `10MB`              | Largest image, stylesheet, script or font                 |
| `archive.limits.max-assets-per-job`     | `2000`              | Assets captured per job; references beyond stay live      |
| `archive.limits.max-redirects`          | `5`                 | Redirects followed per request                            |
| `archive.limits.connect-timeout`        | `5s`                | TCP connect timeout                                       |
| `archive.limits.request-timeout`        | `20s`               | Total time for one request, including the body            |
| `archive.fetch.user-agent`              | `SiteArchiveBot/1.0`| User-Agent sent to websites                               |
| `archive.retry.max-attempts`            | `3`                 | Attempts per page (and for the sitemap)                   |
| `archive.retry.initial-backoff`         | `1s`                | Wait before the second attempt                            |
| `archive.retry.multiplier`              | `2.0`               | Backoff growth per attempt                                |
| `archive.retry.max-backoff`             | `10s`               | Upper bound for a single wait                             |
| `archive.worker.concurrency`            | `2`                 | Worker threads, i.e. jobs processed in parallel           |
| `archive.worker.poll-interval`          | `2s`                | How long an idle worker waits before checking again       |
| `archive.worker.max-crashes-per-job`    | `3`                 | Crashes or fatal errors during a job before it is failed  |

## API

All endpoints are versioned in the path (`/api/v1/...`) using Spring Framework 7 API versioning. The API
has no authentication (see [Limitations](#limitations)). Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)
problem details (`application/problem+json`).

| Method | Path                               | Purpose                                      |
|--------|------------------------------------|----------------------------------------------|
| `POST` | `/api/v1/archives`                 | Create an archive job                        |
| `GET`  | `/api/v1/archives/{id}`            | Status, progress, timestamps, error summary  |
| `GET`  | `/api/v1/archives`                 | All jobs, newest first                       |
| `GET`  | `/api/v1/archives/{id}/pages`      | Outcome of each page                         |
| `GET`  | `/api/v1/archives/{id}/download`   | Download the finished ZIP                    |

The list endpoints accept `page` (0-based, default `0`), `size` (default `20`, at most `100`) and an optional
`status` filter. Paging is bound by Spring Data (`spring.data.web.pageable.*`): a larger `size` is reduced to
`100`, and an invalid `page` or `size` falls back to the default. The order is fixed (jobs newest first, pages
in sitemap order), so a `sort` parameter is ignored.

### Create an archive

```bash
curl -i -X POST http://localhost:8080/api/v1/archives \
  -H 'Content-Type: application/json' \
  -d '{"sitemapUrl": "https://example.org/sitemap.xml", "maxPages": 50}'
```

```http
HTTP/1.1 202 Accepted

{"id":"0b7e3c55-5d0e-4a8e-a0a4-2f3bb1a1e4c9","status":"QUEUED","createdAt":"2026-10-27T18:00:00Z"}
```

- `maxPages` is optional. It defaults to `archive.limits.default-max-pages`, and values above
  `archive.limits.max-pages-limit` are rejected with `400`.

### Check status

```bash
curl -s http://localhost:8080/api/v1/archives/{id}
```

```json
{
  "id": "0b7e3c55-5d0e-4a8e-a0a4-2f3bb1a1e4c9",
  "status": "COMPLETED_WITH_ERRORS",
  "sitemapUrl": "https://example.org/sitemap.xml",
  "maxPages": 50,
  "siteOrigin": "https://example.org",
  "sitemapUrlCount": 12,
  "excludedUrlCount": 1,
  "progress": {"totalPages": 11, "pendingPages": 0, "succeededPages": 10, "failedPages": 1, "percentComplete": 100},
  "errorSummary": "1 of 11 pages could not be captured. See the page list for details.",
  "archiveAvailable": true,
  "archiveSizeBytes": 48213,
  "createdAt": "2026-10-27T18:00:00Z",
  "startedAt": "2026-10-27T18:00:01Z",
  "finishedAt": "2026-10-27T18:00:09Z"
}
```

### List jobs and pages

```bash
curl -s 'http://localhost:8080/api/v1/archives?status=COMPLETED&page=0&size=20'
curl -s 'http://localhost:8080/api/v1/archives/{id}/pages?status=FAILED'
```

Each page reports its `status` (`PENDING`, `SUCCEEDED`, `FAILED`), `attempts`, `httpStatus`, `finalUrl`,
`contentType`, `sizeBytes`, the `fileName` inside the ZIP, and an `error` message. Assets are not part of the
API; the archive's `manifest.json` lists each one with its file or error.

### Download

```bash
curl -OJ http://localhost:8080/api/v1/archives/{id}/download
```

| Situation                                   | Response                                            |
|---------------------------------------------|-----------------------------------------------------|
| Status `COMPLETED` or `COMPLETED_WITH_ERRORS` | `200` and the ZIP as an `attachment`               |
| Status `QUEUED`, `RUNNING` or `FAILED`      | `409 Conflict` with the current status              |
| Finished, but the ZIP file is gone from disk | `410 Gone`                                         |

### Errors

| Status | When                                                                                  |
|--------|---------------------------------------------------------------------------------------|
| `400`  | Validation errors, including an unsupported API version                               |
| `404`  | Unknown archive                                                                       |
| `409`  | The archive is not downloadable                                                       |
| `410`  | The archive file is missing                                                           |

Validation errors always share this format:

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 400,
  "detail": "The request contains invalid values",
  "instance": "/api/v1/archives",
  "errors": [{"field": "maxPages", "message": "must not exceed 1000"}]
}
```

## Job behaviour

```
QUEUED ──> RUNNING ──> COMPLETED
              │  ├───> COMPLETED_WITH_ERRORS
              │  └───> FAILED
              └──────> QUEUED   (on shutdown, or when recovering after a crash)
```

1. **Claiming.** A fixed number of workers (`worker.concurrency`, each on a virtual thread) each run the same loop: claim the
   oldest queued job and process it, or wait `worker.poll-interval` if nothing is queued. A worker only claims
   a job while it is idle, so a job is never marked `RUNNING` before it actually starts. The claim is a single
   transaction: `SELECT … FOR UPDATE SKIP LOCKED` on the oldest queued job, then a status change to `RUNNING`.
   `SKIP LOCKED` means two workers claiming at the same moment never get the same job.
2. **Sitemap.**
   - The sitemap is downloaded and parsed.
   - Each valid entry is normalised (lower-case scheme and host, no default port, no fragment) and
     deduplicated.
   - The first `maxPages` URLs that belong to the sitemap's origin are recorded as `PENDING` pages, in the same
     transaction that records the sitemap counts.
3. **Pages.** Pages are captured one at a time. Each attempt is counted in the database before it starts.
   - A successful page is streamed to disk unmodified, never held in memory.
   - Transient failures are retried with exponential backoff (Spring's `ExponentialBackOff`), up to
     `retry.max-attempts`: network errors,
     timeouts, `429` and `5xx`. No database transaction is open while waiting.
   - Permanent failures fail the page immediately: other `4xx`, non-HTML content, responses whose declared
     `Content-Length` is over the limit, blocked destinations, and redirects off the origin. A body without a
     declared length that grows past the limit is cut off and retried like a network error.
   - A failed page never stops the job.
4. **Assets.** Once all pages are done, the captured pages are read back from disk and the assets they use
   from the site's origin are captured one at a time (see [Assets](#assets)). A captured stylesheet's own
   references (`@import`, fonts, images) are added to the same in-memory queue. Retries and failure rules are
   the same as for pages, except that any content type is accepted and the size limit is `max-asset-size`.
   Nothing about assets is stored in the database, and a failed asset never fails its page or the job.
5. **Outcome.** The status depends on pages only.
   - `COMPLETED` when every page was captured.
   - `COMPLETED_WITH_ERRORS` when some pages failed.
   - `FAILED` when the sitemap could not be processed (download error, sitemap index, compressed sitemap,
     invalid XML, no usable URLs), no page could be captured, or an internal error stopped the job (a bug, or
     the database failing mid-job). The job's `errorSummary` explains why.

Network calls, backoff and ZIP generation all happen outside database transactions. Each state change
(claim, attempt started, page outcome, job outcome) is its own short transaction.

### Restarts and recovery

- **Shutdown.** Running workers are interrupted and hand their jobs back to the queue (`QUEUED`), so they resume
  after the restart.
- **Unexpected errors.** A job stopped by an exception is marked `FAILED`. Only if even that is impossible (the
  database is unreachable) does it stay `RUNNING` until the next start.
- **Fatal errors.** An `Error` counts as a crash of the job, which may or may not be at fault, so it is not marked
  `FAILED` straight away: its crash count goes up and it is requeued. Once the count reaches
  `worker.max-crashes-per-job`, it is marked `FAILED`, since it may be the cause itself. The worker carries on; after
  an `Error` outside a job, it waits `worker.poll-interval` first, so that a recurring error cannot spin it.
  - `OutOfMemoryError`: start the JVM with `-XX:+ExitOnOutOfMemoryError`, so that the whole process exits at once,
    and run it under a supervisor that restarts it (Docker restart policy, systemd, Kubernetes). The job stays
    `RUNNING` and is recovered on the next start.
  - Any other `Error`, such as a `StackOverflowError` on a deeply nested page, is handled at once in the running
    process.
- **Startup.** A job that is still `RUNNING` was not handed back, so the previous process crashed or was killed
  during it. Before the workers start, each such job counts a crash in the same way: it is moved back to `QUEUED`
  to be processed again, or marked `FAILED` once it reaches `worker.max-crashes-per-job` crashes, as otherwise a job
  that brings the service down would do so on every start. Re-running a job is safe:
  - the sitemap stage runs only once per job, and the unique `(archive_job_id, source_url)` constraint
    prevents duplicate page records;
  - pages that already succeeded are skipped, and attempt counts carry over, so a page cannot be retried
    without bound across crashes;
  - page files have deterministic names and every file is replaced atomically, so output never conflicts;
  - assets are not tracked, so every run deletes the job's `assets/` folder and captures them again.
- **Database/file system inconsistencies.**
  - A page marked as captured whose file is missing (e.g. deleted by hand) is not captured again: the job
    fails with an internal error when its assets or ZIP are built.
  - A file written just before a crash, before the database was updated, is simply overwritten.
  - Leftover temporary files are deleted.
  - If a finished job's ZIP has been removed, download returns `410`.

**Single-instance assumption.** Recovery treats every `RUNNING` job as interrupted, which is only true when
exactly one instance runs. The claim query (`SKIP LOCKED`) is already safe for several instances. Recovery
would need worker leases or heartbeats instead, and distributed coordination is out of scope.

## Sitemap and capture scope

- **Supported input:** exactly one XML sitemap with a `<urlset>` root in the
  `http://www.sitemaps.org/schemas/sitemap/0.9` namespace. A document without a namespace is tolerated.
  - Only `<loc>` elements directly inside `<url>` count. Extensions in other namespaces, such as
    `<image:loc>` or `<xhtml:link>`, are ignored.
- **Rejected with an explanation:** sitemap indexes (`<sitemapindex>`) and compressed sitemaps (a `.gz` path,
  a gzip content type, or gzip bytes).
- **Parsing safety:** the XML parser has DTDs and external entities disabled, which prevents XXE and entity
  expansion.
- **What gets downloaded:**
  - Only URLs listed in the sitemap. Links inside pages are never followed.
  - Only pages on the sitemap's origin (scheme, host, port), taken from the sitemap's final URL after
    redirects. Redirects to another origin fail the page.
  - At most the first `maxPages` of them, in sitemap order.
  - Every other entry (invalid, off-origin or beyond `maxPages`) is counted as `excludedUrlCount`, so
    `sitemapUrlCount` = pages + `excludedUrlCount`.
  - Only HTML: `text/html` or `application/xhtml+xml`.
  - The assets those pages use, if they are on the same origin; see below.
- **Network restrictions:** only `http` and `https`. Destination addresses are **not** restricted, so private
  and internal hosts can be fetched; see [Local use only](#site-archive). Redirects are followed by the
  application, not the HTTP client, so every hop of a page or asset request is checked against the site origin.
- **Limits:** configurable caps on sitemap size, page size, page count, asset size, assets per job, redirects
  and total request time.

### Assets

- **Found in pages:** `src` of `<img>`, `<script>` and `<input type="image">`; `poster` of `<video>`;
  `srcset` of `<img>` and `<picture><source>`; `<link href>` for stylesheets, icons, module preloads and
  preloads of styles, scripts, fonts and images; `url(...)` and `@import` in `<style>` elements and `style`
  attributes. Relative references resolve against the page's final URL or its `<base href>`.
- **Found in stylesheets:** `url(...)` and `@import`, resolved against the stylesheet's URL. This is how fonts
  are found. References inside CSS comments are ignored.
- **Local or third-party:** an asset is captured only if it is on the site's origin (scheme, host and port),
  the same rule as for pages, and redirects must stay on it. Everything else, including a CDN on another
  host, is third-party and is not downloaded.
- **Each asset once per job:** a stylesheet used by every page is captured once. At most
  `max-assets-per-job` assets are captured; stylesheets are found first.
- **Rewriting.** Files on disk stay byte-for-byte as served. When the ZIP is built:
  - references to captured assets point to their copy (for example `../assets/4-css-site.css`), and
    `integrity` and `crossorigin` are dropped from those elements;
  - other relative references, such as a local asset that failed, become absolute URLs to the live site, so
    they keep working; absolute third-party references are kept as they are;
  - pages are parsed and serialised again with jsoup, so their formatting can differ slightly; stylesheets
    keep every byte except the rewritten references;
  - a `<base>` element is removed, and the relative links it affected are made absolute.

## Storage layout

```
data/archives/
└── {archiveId}/
    ├── .staging/                 downloads in progress and incomplete writes (cleared when a run starts)
    ├── pages/
    │   ├── 1-index.html          captured HTML, byte-for-byte as served
    │   └── 2-about.html
    ├── assets/
    │   ├── 1-css-site.css        captured assets, byte-for-byte as served (recreated on every run)
    │   └── 2-fonts-site.woff2
    └── archive.zip               index.html + manifest.json + pages/ + assets/, with references rewritten
```

The ZIP's `manifest.json` lists source URL → file, status, HTTP status, content type, attempts and errors for
every page and asset, and its `index.html` links to all captured pages. Both are generated straight into the
ZIP.

- **File names** are generated as `{pageId}-{slug}.html` and `{assetNumber}-{slug}.{extension}`. Remote paths are
  never used as file system paths; the slug is reduced to `[a-z0-9-]` and only aids humans. An asset's
  extension comes from its Content-Type, or else from its URL, because browsers opening files from disk use
  it to decide the type.
- **Atomic writes.** Every file is written to `.staging/` and then atomically moved into place, so a
  published file is always complete.
- **Database references.** The database stores paths relative to the storage root, and they are checked to
  stay inside it.
- **Downloads.** The ZIP is served as an `attachment` with `X-Content-Type-Options: nosniff`. Captured HTML is
  never served as application content.
- **Version control.** `data/` is git-ignored.

## Persistence

Liquibase owns the schema (`src/main/resources/db/changelog`), and Hibernate only validates it
(`ddl-auto: validate`).

- **`archive_job`:** request parameters, status, sitemap counts, error summary, ZIP reference, crash count, timestamps and
  an optimistic-lock version.
  - partial index `(created_at, id) WHERE status = 'QUEUED'` for claiming jobs
  - `(created_at DESC, id DESC)` for job history
- **`archive_page`:** source URL, status, attempts, HTTP status, final URL, content type, file name, size,
  error and timestamps.
  - `UNIQUE (archive_job_id, source_url)`
  - `(archive_job_id, status, id)` for page listing
Assets are not stored in the database; see [Assets](#assets).

Progress is derived from page rows with one grouped `COUNT(...) FILTER (WHERE status = ...)` query (batched
for job lists), so there are no counters to keep in sync.

## Design decisions

### Code organisation

- **Packages by feature.** Each top-level package is one feature or shared concern:
  - `archive`: the archive job model and its persistence (entities, statuses, repositories), plus
    `ArchiveJobLifecycle`, the transactional state changes used by the worker
  - `archive.api`: the REST API for archives (controller and service), with
    `archive.api.dto` for request and response records (with their validation constraints in
    `archive.api.dto.validation`) and `archive.api.error` for its error responses
  - `processing`: the background workers (job claiming, orchestration, retries)
  - `sitemap`, `fetch` and `storage`: sitemap parsing, safe HTTP fetching and the file system layout
  - `assets`: finding and rewriting asset references in HTML (with jsoup) and CSS
  - `config` and `web`: cross-cutting configuration and error handling
- **Visibility marks the boundaries.** Splitting `archive.api` out means `archive` must make some things
  public: entity getters, `ArchiveJob.queue`, the repositories and the status enums. The methods that change
  job and page state stay package-private, so `ArchiveJobLifecycle` remains the only way to modify them. In
  `archive.api`, only the DTOs and error types are public, because its own subpackages hold them; the
  controller and services are package-private.
- **Dependencies point one way.** `archive.api` depends on `archive`, features depend on shared packages, and
  there are no cycles. Feature exceptions extend `ErrorResponseException`, so `web` renders them without
  knowing about them.
- **Controllers only translate HTTP,** and entities guard their own status transitions.
- **Short, visible transactions.** The worker's database work goes through `ArchiveJobLifecycle`, where each
  method is one transaction. `ArchiveJobProcessor` orchestrates around those calls and holds no transaction.
- **No stored progress counters.** Counting page rows is cheap with the `(archive_job_id, status)` index, and
  it is always consistent.
- **Testability.** Dependencies are injected through constructors, including `Clock`, so tests can control
  time. Retry backoff and the worker poll interval come from configuration, so tests can shorten them (e.g.
  `archive.retry.initial-backoff=0ms`).

### Platform choices

- **JDK `HttpClient` with manual redirects.** Following redirects in application code is the only way to
  re-check every hop against the network and origin rules. Oversized bodies are refused from their declared
  `Content-Length` before they are read, or cut off while still streaming by Java 25's
  `BodySubscribers.limiting`.
- **Path-based API versioning** uses Spring Framework 7's built-in support, configured in `application.yml`:
  `spring.mvc.apiversion.use.path-segment` says where the version is, and `spring.mvc.apiversion.supported`
  lists the versions accepted. Mappings carry no `version` attribute, so every endpoint serves each supported
  version; a future v2 of one endpoint would add `version` attributes again.

### Request handling

- **Retries are a short loop in `Retrier`,** with delays from Spring's `ExponentialBackOff`. Each call gets
  its own attempt budget, because a page's attempts are persisted and carry over across restarts. Spring's
  `RetryTemplate` and `@Retryable` were not used: `RetryTemplate` wraps failures, so checked exceptions and
  interrupts had to be unwrapped again, and `@Retryable` has a fixed limit and only works through a proxy.
- **Workers pull jobs instead of a poller pushing them.** Each worker claims its next job only when it is
  idle, so there is no capacity to track and no claimed job waiting in an executor queue. The tradeoff is that
  each idle worker polls the database on its own (one cheap indexed query per worker per poll interval).
- **`maxPages` above the limit is rejected,** not silently clamped, so clients are never surprised by a
  smaller archive.
- **Only URLs selected for capture become page rows.** Excluded entries are counted on the job instead, which
  keeps the page table bounded by `maxPages`.

## Limitations

- **No SSRF protection — local use only:** submitted sitemaps, the pages they list and redirects can point at
  loopback, private-network or cloud metadata addresses, and the responses end up in the downloadable archive.
  Error messages stored per page (connection refused, timeouts, status codes) also reveal which internal hosts
  and ports exist. Before making the service reachable by others, block internal destinations, ideally both:
  - at the network level: an egress proxy or firewall that denies private, loopback, link-local and metadata
    ranges, which also covers DNS rebinding;
  - in the application: reject internal addresses at DNS-resolution time, on every redirect hop, including
    IPv4 addresses embedded in IPv6 (`::ffff:0:0/96`, NAT64 `64:ff9b::/96`, 6to4 `2002::/16`).
- **No authentication or ownership:** anyone who can reach the API can create, list and download every
  archive. Run it only on a trusted network or behind a gateway that authenticates callers.
- **Distribution and lifecycle:** single instance only (see above). There is no cleanup or retention policy
  for old archives, and no cancellation endpoint.
- **Network:**
  - `Retry-After` headers are not honoured, and backoff is purely exponential.
  - There is no `robots.txt` handling, per-site rate limiting or politeness delay beyond sequential page
    fetching.
- **Sitemap input:** sitemap indexes, compressed sitemaps, and text or RSS sitemaps are not supported.
- **Assets:** only the references listed under [Assets](#assets). Lazy-loading attributes such as `data-src`,
  SVG `<image>`/`<use>`, `<audio>`/`<video>` media, JavaScript `import`s and files that scripts fetch at
  runtime are not captured. Assets skipped because of `max-assets-per-job` are only reported in the log.
- **Memory:** pages and assets are streamed to disk, but some files are still read into memory whole: the
  sitemap (up to `max-sitemap-size`), every page while its assets are found and while the ZIP is built (parsed
  with jsoup, which takes several times the file size), and stylesheets (up to `max-asset-size`).
- **Not built yet:** tests cover only asset link finding, rewriting and file naming; no metrics or health
  endpoint.
