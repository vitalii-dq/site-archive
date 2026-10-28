package org.archive.sitearchive.assets;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class CssAssetLinksTest {

    private static final URI STYLESHEET = URI.create("https://example.org/css/site.css");

    @Test
    void findsUrlsAndImportsButSkipsCommentsAndDataUrls() {
        String css = """
                @import "base.css";
                @import url(https://fonts.example.com/css?family=Sans);
                /* background: url(old.png); */
                @font-face { src: url('../fonts/a.woff2') format("woff2"), url(../fonts/a.woff); }
                .logo { background: url("data:image/svg+xml;utf8,<svg/>"), url(#gradient); }
                """;

        assertThat(CssAssetLinks.find(bytes(css), STYLESHEET)).extracting(URI::toString).containsExactly(
                "https://example.org/css/base.css",
                "https://fonts.example.com/css?family=Sans",
                "https://example.org/fonts/a.woff2",
                "https://example.org/fonts/a.woff");
    }

    @Test
    void rewritesReferencesAndLeavesEverythingElseUntouched() {
        String css = "@import 'base.css';\n.a{background:url( ../img/a.png )}\n.b{background:url(../img/gone.png)}\n"
                + ".c{background:url(https://cdn.example.com/c.png)}\n/* url(../img/a.png) */\n.d::after{content:\"✓\"}";
        Map<String, String> copies = Map.of(
                "https://example.org/css/base.css", "2-css-base.css",
                "https://example.org/img/a.png", "3-img-a.png");

        byte[] rewritten = CssAssetLinks.rewrite(bytes(css), STYLESHEET, url -> copies.get(url.toString()));

        assertThat(new String(rewritten, StandardCharsets.UTF_8)).isEqualTo(
                "@import \"2-css-base.css\";\n.a{background:url(\"3-img-a.png\")}\n"
                        + ".b{background:url(\"https://example.org/img/gone.png\")}\n"
                        + ".c{background:url(https://cdn.example.com/c.png)}\n/* url(../img/a.png) */\n"
                        + ".d::after{content:\"✓\"}");
    }

    @Test
    void keepsBytesThatAreNotUtf8() {
        byte[] css = ".a::after{content:\"café\"}.b{background:url(a.png)}".getBytes(StandardCharsets.ISO_8859_1);

        byte[] rewritten = CssAssetLinks.rewrite(css, STYLESHEET, url -> "1-a.png");

        assertThat(rewritten).isEqualTo(".a::after{content:\"café\"}.b{background:url(\"1-a.png\")}"
                .getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void treatsAnUnclosedCommentAsRunningToTheEnd() {
        String css = ".a{background:url(a.png)}\n/* unclosed\n.b{background:url(b.png)}";

        assertThat(CssAssetLinks.find(bytes(css), STYLESHEET)).extracting(URI::toString)
                .containsExactly("https://example.org/css/a.png");
    }

    @Test
    void takesLinearTimeOnHostileInput() {
        // Both took tens of seconds at this size when every match attempt rescanned the rest of the input.
        for (String css : List.of("/* ".repeat(70_000), "url(".repeat(50_000))) {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> CssAssetLinks.find(bytes(css), STYLESHEET));
        }
    }

    @Test
    void recognisesStylesheets() {
        assertThat(CssAssetLinks.isStylesheet("text/css; charset=utf-8")).isTrue();
        assertThat(CssAssetLinks.isStylesheet("text/plain")).isFalse();
        assertThat(CssAssetLinks.isStylesheet(null)).isFalse();
        assertThat(CssAssetLinks.isStylesheet("not a type")).isFalse();
    }

    private static byte[] bytes(String css) {
        return css.getBytes(StandardCharsets.UTF_8);
    }
}
