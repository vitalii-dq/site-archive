package org.archive.sitearchive.assets;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlAssetLinksTest {

    private static final URI PAGE = URI.create("https://example.org/blog/post.html");

    @Test
    void findsAssetsInEveryCoveredPlace() {
        String html = """
                <html><head>
                  <link rel="stylesheet" href="/css/site.css">
                  <link rel="icon" href="favicon.ico">
                  <link rel="preload" as="font" href="/fonts/a.woff2">
                  <link rel="preload" as="fetch" href="/api/data.json">
                  <link rel="canonical" href="/blog/post.html">
                  <style>body { background: url('/img/bg.png') } /* url(/img/commented.png) */</style>
                  <script src="app.js"></script>
                </head><body style="background-image: url(paper.jpg)">
                  <img src="/img/a.png" srcset="/img/a-1x.png 1x, /img/a-2x.png 2x">
                  <picture><source srcset="/img/b.webp"></picture>
                  <video poster="/img/poster.jpg"></video>
                  <a href="/about.html">About</a>
                  <img src="data:image/png;base64,AAAA">
                  <img src="https://cdn.example.com/x.png">
                </body></html>
                """;

        HtmlAssetLinks.Found found = HtmlAssetLinks.find(bytes(html), "text/html", PAGE);

        assertThat(found.styling()).extracting(URI::toString).containsExactlyInAnyOrder(
                "https://example.org/css/site.css",
                "https://example.org/blog/paper.jpg",
                "https://example.org/img/bg.png");
        assertThat(found.other()).extracting(URI::toString).containsExactlyInAnyOrder(
                "https://example.org/img/a.png",
                "https://example.org/blog/app.js",
                "https://cdn.example.com/x.png",
                "https://example.org/img/poster.jpg",
                "https://example.org/blog/favicon.ico",
                "https://example.org/fonts/a.woff2",
                "https://example.org/img/a-1x.png",
                "https://example.org/img/a-2x.png",
                "https://example.org/img/b.webp");
    }

    @Test
    void resolvesAgainstTheBaseElement() {
        String html = "<html><head><base href=\"https://example.org/static/\"></head><body><img src=\"a.png\"></body></html>";

        assertThat(HtmlAssetLinks.find(bytes(html), "text/html", PAGE).other())
                .containsExactly(URI.create("https://example.org/static/a.png"));
    }

    @Test
    void rewritesLocalCopiesAndMakesOtherRelativeReferencesAbsolute() {
        String html = """
                <html><head>
                <link rel="stylesheet" href="/css/site.css" integrity="sha384-abc" crossorigin="anonymous">
                <script src="https://cdn.example.com/lib.js" integrity="sha384-def" crossorigin="anonymous"></script>
                <script src="//cdn.example.com/other.js"></script>
                </head><body>
                <img src="/img/a.png"><img src="/img/missing.png"><img src="/img/icons.svg#home">
                <a href="/about.html">About</a>
                </body></html>
                """;
        Map<String, String> copies = Map.of(
                "https://example.org/css/site.css", "../assets/1-css-site.css",
                "https://example.org/img/a.png", "../assets/2-img-a.png",
                "https://example.org/img/icons.svg", "../assets/3-img-icons.svg");

        String rewritten = rewrite(html, url -> copies.get(url.toString()));

        assertThat(rewritten)
                .contains("<link rel=\"stylesheet\" href=\"../assets/1-css-site.css\">")
                .contains("<script src=\"https://cdn.example.com/lib.js\" integrity=\"sha384-def\" crossorigin=\"anonymous\">")
                .contains("<script src=\"https://cdn.example.com/other.js\">")
                .contains("<img src=\"../assets/2-img-a.png\">")
                .contains("<img src=\"https://example.org/img/missing.png\">")
                .contains("<img src=\"../assets/3-img-icons.svg#home\">")
                .contains("<a href=\"/about.html\">About</a>");
    }

    @Test
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

    @Test
    void keepsTheDeclaredCharset() {
        byte[] html = "<html><head><meta charset=\"iso-8859-1\"></head><body>café<img src=\"a.png\"></body></html>"
                .getBytes(StandardCharsets.ISO_8859_1);

        byte[] rewritten = HtmlAssetLinks.rewrite(html, "text/html", PAGE, url -> "../assets/1-a.png");

        assertThat(new String(rewritten, StandardCharsets.ISO_8859_1))
                .contains("café")
                .contains("<img src=\"../assets/1-a.png\">");
    }

    @Test
    void rewritesEachSrcsetCandidate() {
        String srcset = "a.png 1x, https://example.org/w_100,h_100/b.png 2x,c.png";

        String rewritten = HtmlAssetLinks.rewriteSrcset(srcset, url -> "[" + url + "]");

        assertThat(rewritten).isEqualTo("[a.png] 1x, [https://example.org/w_100,h_100/b.png] 2x,[c.png]");
    }

    private static String rewrite(String html, Function<URI, String> localCopy) {
        return new String(HtmlAssetLinks.rewrite(bytes(html), "text/html; charset=utf-8", PAGE, localCopy),
                StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String html) {
        return html.getBytes(StandardCharsets.UTF_8);
    }
}
