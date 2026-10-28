package org.archive.sitearchive.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IndexPageRendererTest {

    @Test
    @DisplayName("Links every captured page, escaped, and counts captured pages and assets, noting skipped assets")
    void linksCapturedPagesAndCountsEverything() {
        ArchiveManifest manifest = new ArchiveManifest(UUID.randomUUID(), "https://example.org/sitemap.xml",
                "https://example.org", Instant.parse("2026-10-28T10:00:00Z"), ArchiveManifest.SNAPSHOT_NOTICE,
                List.of(new ArchiveManifest.Entry("https://example.org/search?q=<b>&x=\"y\"", "CAPTURED",
                                "pages/1-search.html", 200, "text/html", "https://example.org/search", 1, null),
                        new ArchiveManifest.Entry("https://example.org/broken", "FAILED", null, 500, null, null, 3,
                                "HTTP status 500")),
                List.of(new ArchiveManifest.Asset("https://example.org/a.png", "assets/1-a.png",
                                "https://example.org/a.png", "image/png", null),
                        new ArchiveManifest.Asset("https://example.org/b.png", null, null, null, "HTTP status 404")),
                3);

        String html = IndexPageRenderer.render(manifest);

        assertThat(html)
                .contains("<li><a href=\"pages/1-search.html\">"
                        + "https://example.org/search?q=&lt;b&gt;&amp;x=&quot;y&quot;</a></li>")
                .doesNotContain("https://example.org/broken")
                .contains("Captured pages: 1 of 2")
                .contains("Captured assets: 1 of 2 (at least 3 more not attempted: the asset limit was reached)");
    }
}
