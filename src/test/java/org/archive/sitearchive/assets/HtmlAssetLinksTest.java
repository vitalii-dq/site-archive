package org.archive.sitearchive.assets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlAssetLinksTest {

    private static final URI PAGE = URI.create("https://example.org/blog/post.html");

    @Test
    @DisplayName("Finds assets in every covered place in document order, styling apart from the rest, and skips "
            + "links between pages")
    void findsAssetsInEveryCoveredPlace() {
        String html = """
                <html><head>
                  <link rel="stylesheet" href="/css/site.css">
                  <link rel="preload" as="style" href="/css/print.css">
                  <link rel="Shortcut Icon" href="favicon.ico">
                  <link rel="preload" as="font" href="/fonts/a.woff2">
                  <link rel="preload" as="fetch" href="/api/data.json">
                  <link rel="canonical" href="/blog/post.html">
                  <style>body { background: url('/img/bg.png') } /* url(/img/commented.png) */</style>
                  <script src="app.js"></script>
                </head><body style="background-image: url(paper.jpg)">
                  <img src="/img/a.png" srcset="/img/a-1x.png 1x, /img/a-2x.png 2x">
                  <picture><source srcset="/img/b.webp"></picture>
                  <video poster="/img/poster.jpg"></video>
                  <input type="image" src="/img/submit.png">
                  <a href="/about.html">About</a>
                  <img src="https://cdn.example.com/x.png">
                </body></html>
                """;

        HtmlAssetLinks.Found found = HtmlAssetLinks.find(bytes(html), "text/html", PAGE);

        assertThat(found.styling()).extracting(URI::toString).containsExactly(
                "https://example.org/css/site.css",
                "https://example.org/css/print.css",
                "https://example.org/img/bg.png",
                "https://example.org/blog/paper.jpg");
        assertThat(found.other()).extracting(URI::toString).containsExactly(
                "https://example.org/blog/favicon.ico",
                "https://example.org/fonts/a.woff2",
                "https://example.org/blog/app.js",
                "https://example.org/img/a.png",
                "https://example.org/img/a-1x.png",
                "https://example.org/img/a-2x.png",
                "https://example.org/img/b.webp",
                "https://example.org/img/poster.jpg",
                "https://example.org/img/submit.png",
                "https://cdn.example.com/x.png");
    }

    @ParameterizedTest
    @CsvSource({
            "https://example.org/static/, https://example.org/static/a.png",
            "/static/,                    https://example.org/static/a.png",
            "ftp://example.org/static/,   https://example.org/blog/a.png",
            "http://[invalid/,            https://example.org/blog/a.png"
    })
    @DisplayName("Resolves against the base element, or against the page if the base is not an http(s) URL")
    void resolvesAgainstTheBaseElement(String baseHref, String resolved) {
        String html = "<html><head><base href=\"" + baseHref + "\"></head><body><img src=\"a.png\"></body></html>";

        assertThat(HtmlAssetLinks.find(bytes(html), "text/html", PAGE).other()).containsExactly(URI.create(resolved));
    }

    @Test
    @DisplayName("Points references at local copies, makes other relative ones absolute and drops integrity checks "
            + "only for local copies")
    void rewritesLocalCopiesAndMakesOtherRelativeReferencesAbsolute() {
        String html = """
                <html><head>
                <link rel="stylesheet" href="/css/site.css" integrity="sha384-abc" crossorigin="anonymous">
                <script src="https://cdn.example.com/lib.js" integrity="sha384-def" crossorigin="anonymous"></script>
                <style>.a > .b { background: url(/img/bg.png) }</style>
                </head><body>
                <img src="/img/a.png">
                <img srcset="/img/a.png 1x, /img/missing.png 2x">
                <div style="background: url(/img/bg.png)"></div>
                <a href="/about.html">About</a>
                </body></html>
                """;
        Map<String, String> copies = Map.of(
                "https://example.org/css/site.css", "../assets/1-css-site.css",
                "https://example.org/img/a.png", "../assets/2-img-a.png",
                "https://example.org/img/bg.png", "../assets/3-img-bg.png");

        String rewritten = rewrite(html, url -> copies.get(url.toString()));

        assertThat(rewritten)
                .contains("<link rel=\"stylesheet\" href=\"../assets/1-css-site.css\">")
                .contains("<script src=\"https://cdn.example.com/lib.js\" integrity=\"sha384-def\" crossorigin=\"anonymous\">")
                .contains("<style>.a > .b { background: url(\"../assets/3-img-bg.png\") }</style>")
                .contains("<img src=\"../assets/2-img-a.png\">")
                .contains("<img srcset=\"../assets/2-img-a.png 1x, https://example.org/img/missing.png 2x\">")
                .contains("<div style=\"background: url(&quot;../assets/3-img-bg.png&quot;)\"></div>")
                .contains("<a href=\"/about.html\">About</a>");
    }

    @Test
    @DisplayName("Removes the base element and makes the relative links it affected absolute")
    void removesTheBaseElementAndKeepsTheLinksItAffected() {
        String html = "<html><head><base href=\"https://example.org/static/\"></head>"
                + "<body><img src=\"a.png\"><a href=\"page.html\">Page</a><a href=\"#top\">Top</a></body></html>";

        String rewritten = rewrite(html, url -> url.toString().endsWith("/a.png") ? "../assets/1-a.png" : null);

        assertThat(rewritten)
                .doesNotContain("<base")
                .contains("<img src=\"../assets/1-a.png\">")
                .contains("<a href=\"https://example.org/static/page.html\">Page</a>")
                .contains("<a href=\"#top\">Top</a>");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "text/html                        | <meta charset=\"iso-8859-1\">",
            "text/html; charset=ISO-8859-1    | ''"
    })
    @DisplayName("Reads and writes the page in the charset of its meta tag or Content-Type")
    void keepsTheDeclaredCharset(String contentType, String head) {
        byte[] html = ("<html><head>" + head + "</head><body>café<img src=\"a.png\"></body></html>")
                .getBytes(StandardCharsets.ISO_8859_1);

        byte[] rewritten = HtmlAssetLinks.rewrite(html, contentType, PAGE, url -> "../assets/1-a.png");

        assertThat(new String(rewritten, StandardCharsets.ISO_8859_1))
                .contains("café")
                .contains("<img src=\"../assets/1-a.png\">");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"not a type", "text/html; charset=unknown"})
    @DisplayName("Ignores a missing or unusable Content-Type instead of failing")
    void ignoresAnUnusableContentType(String contentType) {
        byte[] html = bytes("<html><body><img src=\"a.png\"></body></html>");

        byte[] rewritten = HtmlAssetLinks.rewrite(html, contentType, PAGE, url -> "../assets/1-a.png");

        assertThat(new String(rewritten, StandardCharsets.UTF_8)).contains("<img src=\"../assets/1-a.png\">");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "a.png 1x, https://example.org/w_100,h_100/b.png 2x,c.png | [a.png] 1x, [https://example.org/w_100,h_100/b.png] 2x,[c.png]",
            "'  a.png,  b.png 2x  '                                    | '  [a.png],  [b.png] 2x  '",
            "a.png 480w , b.png 800w                                   | [a.png] 480w , [b.png] 800w"
    })
    @DisplayName("Rewrites each srcset candidate: a URL runs up to whitespace, minus trailing commas, and keeps its "
            + "descriptors")
    void rewritesEachSrcsetCandidate(String srcset, String rewritten) {
        assertThat(HtmlAssetLinks.rewriteSrcset(srcset, url -> "[" + url + "]")).isEqualTo(rewritten);
    }

    private static String rewrite(String html, Function<URI, String> localCopy) {
        return new String(HtmlAssetLinks.rewrite(bytes(html), "text/html; charset=utf-8", PAGE, localCopy),
                StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String html) {
        return html.getBytes(StandardCharsets.UTF_8);
    }
}
