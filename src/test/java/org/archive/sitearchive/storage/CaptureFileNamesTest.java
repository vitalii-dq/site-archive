package org.archive.sitearchive.storage;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureFileNamesTest {

    @Test
    void namesPages() {
        assertThat(CaptureFileNames.forPage(42, URI.create("https://example.org/blog/Hello-World.html")))
                .isEqualTo("pages/42-blog-hello-world.html");
        assertThat(CaptureFileNames.forPage(1, URI.create("https://example.org/"))).isEqualTo("pages/1-index.html");
    }

    @Test
    void takesTheAssetExtensionFromTheContentTypeThenTheUrl() {
        assertThat(CaptureFileNames.forAsset(7, URI.create("https://example.org/style.php?v=2"), "text/css; charset=utf-8"))
                .isEqualTo("assets/7-style.css");
        assertThat(CaptureFileNames.forAsset(8, URI.create("https://example.org/fonts/A.woff2"), "application/octet-stream"))
                .isEqualTo("assets/8-fonts-a.woff2");
        assertThat(CaptureFileNames.forAsset(9, URI.create("https://example.org/image"), null))
                .isEqualTo("assets/9-image.bin");
    }
}
