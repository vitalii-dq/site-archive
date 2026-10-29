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

Requires JDK 25 and Docker.

```bash
./scripts/start-db.sh   # PostgreSQL 17 on localhost:5433 (db/user/password: site_archive)
./gradlew bootRun       # API on http://localhost:8080, archives in ./data/archives
```

Tests (`./gradlew test`) start their own PostgreSQL with Testcontainers, so Docker must be running.

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
# 202 {"id":"0b7e3c55-...","status":"QUEUED","createdAt":"..."}

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

## Limitations

- No SSRF protection or authentication (see the warning above).
- Single instance only: crash recovery assumes no other instance is running.
- No archive cleanup: a finished job keeps its ZIP forever (the pages and assets it was built from are
  deleted once the job completes). No job cancellation, `robots.txt` handling or rate limiting either.
- Lazy-loaded images (`data-src`), SVG `<use>`, `<audio>`/`<video>` media and JavaScript imports are not
  captured.
