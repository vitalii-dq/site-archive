package org.archive.sitearchive.assets;

import org.archive.sitearchive.fetch.HttpUrls;
import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.jsoup.select.Evaluator;
import org.jsoup.select.QueryParser;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Finds and rewrites the images, stylesheets, scripts and fonts an HTML page uses:
 * <ul>
 *   <li>{@code src} of {@code <img>}, {@code <script>} and {@code <input type="image">}, and {@code poster} of
 *       {@code <video>};</li>
 *   <li>{@code srcset} of {@code <img>} and of {@code <source>} inside {@code <picture>};</li>
 *   <li>{@code href} of {@code <link>} for stylesheets, icons, module preloads and preloads of styles, scripts,
 *       fonts and images;</li>
 *   <li>{@code url(...)} and {@code @import} in {@code <style>} elements and {@code style} attributes (fonts are
 *       usually referenced from CSS).</li>
 * </ul>
 * Links between pages ({@code <a href>}) and files that scripts load at runtime are not covered.
 */
public final class HtmlAssetLinks {

    private static final Set<String> ASSET_LINK_TYPES = Set.of(
            "stylesheet", "icon", "apple-touch-icon", "apple-touch-icon-precomposed", "mask-icon", "modulepreload");
    private static final Set<String> PRELOAD_DESTINATIONS = Set.of("style", "script", "font", "image");

    private static final Evaluator LINK = QueryParser.parse("link[href]");
    private static final Evaluator SRC = QueryParser.parse("img[src], script[src], input[type=image][src]");
    private static final Evaluator POSTER = QueryParser.parse("video[poster]");
    private static final Evaluator SRCSET = QueryParser.parse("img[srcset], picture > source[srcset]");
    private static final Evaluator STYLE_ATTRIBUTE = QueryParser.parse("[style]");
    private static final Evaluator STYLE_ELEMENT = QueryParser.parse("style");

    private HtmlAssetLinks() {
    }

    /**
     * The canonical URLs of the http(s) assets a page references, in document order.
     *
     * @param styling stylesheets and the files the page's own CSS uses, which matter most for how it looks
     * @param other   everything else: images, scripts, icons and preloads
     */
    public record Found(Set<URI> styling, Set<URI> other) {
    }

    public static Found find(byte[] html, String contentType, URI pageUrl) {
        Document document = parse(html, contentType, pageUrl);
        URI base = baseUrl(document, pageUrl);
        Found found = new Found(new LinkedHashSet<>(), new LinkedHashSet<>());
        visit(document, ReferenceResolver.collecting(base, found.styling()), ReferenceResolver.collecting(base, found.other()));
        return found;
    }

    /**
     * Points the page's asset references at their local copies, see {@link ReferenceResolver}. The document is
     * parsed and serialised again, so formatting can differ slightly from the original.
     */
    public static byte[] rewrite(byte[] html, String contentType, URI pageUrl, Function<URI, String> localCopy) {
        Document document = parse(html, contentType, pageUrl);
        URI base = baseUrl(document, pageUrl);
        removeBase(document, base);
        ReferenceResolver resolver = new ReferenceResolver(base, localCopy);
        visit(document, resolver, resolver);
        document.outputSettings().prettyPrint(false);
        return document.outerHtml().getBytes(document.charset());
    }

    private static void visit(Document document, UnaryOperator<String> styling, UnaryOperator<String> other) {
        for (Element element : document.getAllElements()) {
            if (element.is(LINK)) {
                if (isStylesheetLink(element)) {
                    replaceAttribute(element, "href", styling);
                } else if (isAssetLink(element)) {
                    replaceAttribute(element, "href", other);
                }
            }
            if (element.is(SRC)) {
                replaceAttribute(element, "src", other);
            }
            if (element.is(POSTER)) {
                replaceAttribute(element, "poster", other);
            }
            if (element.is(SRCSET)) {
                String srcset = element.attr("srcset");
                String rewritten = rewriteSrcset(srcset, other);
                if (!rewritten.equals(srcset)) {
                    element.attr("srcset", rewritten);
                }
            }
            if (element.is(STYLE_ATTRIBUTE)) {
                String css = element.attr("style");
                String rewritten = CssAssetLinks.rewrite(css, styling);
                if (!rewritten.equals(css)) {
                    element.attr("style", rewritten);
                }
            }
            if (element.is(STYLE_ELEMENT)) {
                String css = element.data();
                String rewritten = CssAssetLinks.rewrite(css, styling);
                if (!rewritten.equals(css)) {
                    element.empty().appendChild(new DataNode(rewritten));
                }
            }
        }
    }

    private static void replaceAttribute(Element element, String attribute, UnaryOperator<String> replace) {
        String replacement = replace.apply(element.attr(attribute));
        if (replacement == null) {
            return;
        }
        element.attr(attribute, replacement);
        if (!URI.create(replacement).isAbsolute()) {
            element.removeAttr("integrity").removeAttr("crossorigin");
        }
    }

    private static boolean isStylesheetLink(Element link) {
        List<String> types = linkTypes(link);
        return types.contains("stylesheet") || types.contains("preload") && "style".equalsIgnoreCase(link.attr("as"));
    }

    private static boolean isAssetLink(Element link) {
        List<String> types = linkTypes(link);
        if (types.stream().anyMatch(ASSET_LINK_TYPES::contains)) {
            return true;
        }
        return types.contains("preload") && PRELOAD_DESTINATIONS.contains(link.attr("as").toLowerCase(Locale.ROOT));
    }

    private static List<String> linkTypes(Element link) {
        return Arrays.asList(link.attr("rel").toLowerCase(Locale.ROOT).strip().split("\\s+"));
    }

    /**
     * A srcset is a comma-separated list of "URL [descriptor]" candidates. URLs can contain commas themselves, so,
     * as in the HTML specification, a URL runs up to the next whitespace and only trailing commas end it.
     */
    static String rewriteSrcset(String srcset, UnaryOperator<String> replace) {
        StringBuilder rewritten = new StringBuilder();
        int length = srcset.length();
        int position = 0;
        while (position < length) {
            int separatorStart = position;
            while (position < length && (Character.isWhitespace(srcset.charAt(position)) || srcset.charAt(position) == ',')) {
                position++;
            }
            rewritten.append(srcset, separatorStart, position);
            if (position == length) {
                break;
            }
            int urlStart = position;
            while (position < length && !Character.isWhitespace(srcset.charAt(position))) {
                position++;
            }
            int urlEnd = position;
            while (urlEnd > urlStart && srcset.charAt(urlEnd - 1) == ',') {
                urlEnd--;
            }
            String url = srcset.substring(urlStart, urlEnd);
            String replacement = replace.apply(url);
            rewritten.append(replacement != null ? replacement : url).append(srcset, urlEnd, position);
            if (urlEnd == position) {
                int descriptorsStart = position;
                while (position < length && srcset.charAt(position) != ',') {
                    position++;
                }
                rewritten.append(srcset, descriptorsStart, position);
            }
        }
        return rewritten.toString();
    }

    /**
     * A {@code <base>} would also apply to the rewritten, relative references, so it is removed. The relative
     * references it affected are made absolute first, so that they keep pointing where they did.
     */
    private static void removeBase(Document document, URI base) {
        Elements baseElements = document.select("base[href]");
        if (baseElements.isEmpty()) {
            return;
        }
        baseElements.remove();
        ReferenceResolver absolute = new ReferenceResolver(base, url -> null);
        for (String attribute : List.of("href", "src", "action")) {
            for (Element element : document.select("[" + attribute + "]")) {
                replaceAttribute(element, attribute, absolute);
            }
        }
    }

    /** The page URL, or the URL in its {@code <base href>}, against which relative references resolve. */
    private static URI baseUrl(Document document, URI pageUrl) {
        Element base = document.selectFirst("base[href]");
        if (base == null) {
            return pageUrl;
        }
        try {
            return HttpUrls.parse(HttpUrls.resolve(pageUrl, new URI(base.attr("href").strip())).toString())
                    .orElse(pageUrl);
        } catch (URISyntaxException | IllegalArgumentException e) {
            return pageUrl;
        }
    }

    /** The charset comes from a byte order mark, the Content-Type header or a meta tag, in that order. */
    private static Document parse(byte[] html, String contentType, URI pageUrl) {
        try {
            return Jsoup.parse(new ByteArrayInputStream(html), charsetName(contentType), pageUrl.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String charsetName(String contentType) {
        if (contentType == null) {
            return null;
        }
        try {
            Charset charset = MediaType.parseMediaType(contentType).getCharset();
            return charset == null ? null : charset.name();
        } catch (IllegalArgumentException e) {
            return null; // an invalid media type or an unknown charset
        }
    }
}
