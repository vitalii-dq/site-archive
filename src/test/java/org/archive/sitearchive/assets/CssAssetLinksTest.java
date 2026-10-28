package org.archive.sitearchive.assets;

import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("Finds url() and @import references once each, in order of appearance, but not inside comments")
    void findsUrlsAndImportsButSkipsComments() {
        String css = """
                @import "base.css";
                @import url(https://fonts.example.com/css?family=Sans);
                /* background: url(old.png); */
                @font-face { src: url('../fonts/a.woff2') format("woff2"), url(../fonts/a.woff); }
                .logo { background: URL(../img/logo.png), url("../fonts/a.woff2"); }
                """;

        assertThat(CssAssetLinks.find(bytes(css), STYLESHEET)).extracting(URI::toString).containsExactly(
                "https://example.org/css/base.css",
                "https://fonts.example.com/css?family=Sans",
                "https://example.org/fonts/a.woff2",
                "https://example.org/fonts/a.woff",
                "https://example.org/img/logo.png");
    }

    @Test
    @DisplayName("Rewrites references as quoted URLs and leaves everything else byte-for-byte the same")
    void rewritesReferencesAndLeavesEverythingElseUntouched() {
        String css = "@import 'base.css';\n.a{background:url( ../img/a.png )}\n"
                + ".c{background:url(https://cdn.example.com/c.png)}\n/* url(../img/a.png) */\n.d::after{content:\"✓\"}";
        Map<String, String> copies = Map.of(
                "https://example.org/css/base.css", "2-css-base.css",
                "https://example.org/img/a.png", "3-img-a.png");

        byte[] rewritten = CssAssetLinks.rewrite(bytes(css), STYLESHEET, url -> copies.get(url.toString()));

        assertThat(new String(rewritten, StandardCharsets.UTF_8)).isEqualTo(
                "@import \"2-css-base.css\";\n.a{background:url(\"3-img-a.png\")}\n"
                        + ".c{background:url(https://cdn.example.com/c.png)}\n/* url(../img/a.png) */\n"
                        + ".d::after{content:\"✓\"}");
    }

    @Test
    @DisplayName("Keeps the bytes of a stylesheet that is not UTF-8")
    void keepsBytesThatAreNotUtf8() {
        byte[] css = ".a::after{content:\"café\"}.b{background:url(a.png)}".getBytes(StandardCharsets.ISO_8859_1);

        byte[] rewritten = CssAssetLinks.rewrite(css, STYLESHEET, url -> "1-a.png");

        assertThat(rewritten).isEqualTo(".a::after{content:\"café\"}.b{background:url(\"1-a.png\")}"
                .getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    @DisplayName("Treats an unclosed comment as running to the end")
    void treatsAnUnclosedCommentAsRunningToTheEnd() {
        String css = ".a{background:url(a.png)}\n/* unclosed\n.b{background:url(b.png)}";

        assertThat(CssAssetLinks.find(bytes(css), STYLESHEET)).extracting(URI::toString)
                .containsExactly("https://example.org/css/a.png");
    }

    @Test
    @DisplayName("Takes linear time on hostile input")
    void takesLinearTimeOnHostileInput() {
        // Both took tens of seconds at this size when every match attempt rescanned the rest of the input.
        for (String css : List.of("/* ".repeat(70_000), "url(".repeat(50_000))) {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> CssAssetLinks.find(bytes(css), STYLESHEET));
        }
    }

    @Test
    @DisplayName("Recognises stylesheets by their media type")
    void recognisesStylesheets() {
        assertThat(CssAssetLinks.isStylesheet("text/css; charset=utf-8")).isTrue();
        assertThat(CssAssetLinks.isStylesheet("text/plain")).isFalse();
    }

    private static byte[] bytes(String css) {
        return css.getBytes(StandardCharsets.UTF_8);
    }
}
