package org.archive.sitearchive.assets;

import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds and rewrites the files a stylesheet references with {@code url(...)} and {@code @import}. */
public final class CssAssetLinks {

    // Comments come first so that references inside them are skipped. Stylesheets come from the archived site, so
    // no alternative may rescan the rest of the input from every start position: an unclosed comment runs to the
    // end, as in CSS, and an unquoted URL cannot contain '(', which CSS does not allow there either. Otherwise
    // input such as "/* /* /* ..." or "url(url(url(..." takes quadratic time.
    private static final Pattern REFERENCE = Pattern.compile(
            "/\\*.*?(?:\\*/|\\z)"
                    + "|(?<![\\w-])url\\(\\s*(?:\"([^\"]*)\"|'([^']*)'|([^()\"'\\s]*+))\\s*\\)"
                    + "|@import\\s+(?:\"([^\"]*)\"|'([^']*)')",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private CssAssetLinks() {
    }

    public static boolean isStylesheet(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            return MediaType.parseMediaType(contentType).equalsTypeAndSubtype(MediaType.valueOf("text/css"));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    /** The canonical URLs of all http(s) files the stylesheet references, in order of appearance. */
    public static Set<URI> find(byte[] css, URI stylesheetUrl) {
        Set<URI> found = new LinkedHashSet<>();
        rewrite(new String(css, charsetOf(css)), ReferenceResolver.collecting(stylesheetUrl, found));
        return found;
    }

    /** See {@link ReferenceResolver}; everything except the references stays byte-for-byte the same. */
    public static byte[] rewrite(byte[] css, URI stylesheetUrl, Function<URI, String> localCopy) {
        Charset charset = charsetOf(css);
        return rewrite(new String(css, charset), new ReferenceResolver(stylesheetUrl, localCopy)).getBytes(charset);
    }

    /** Also used for {@code <style>} elements and {@code style} attributes. */
    static String rewrite(String css, UnaryOperator<String> replace) {
        Matcher matcher = REFERENCE.matcher(css);
        StringBuilder rewritten = new StringBuilder();
        while (matcher.find()) {
            String replacement = replacement(matcher, replace);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement != null ? replacement : matcher.group()));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private static String replacement(Matcher matcher, UnaryOperator<String> replace) {
        String url = firstNonNull(matcher.group(1), matcher.group(2), matcher.group(3));
        if (url != null) {
            String replaced = replace.apply(url);
            // Quoted, because a URL may contain characters such as ')' that end an unquoted url().
            return replaced == null ? null : "url(\"" + replaced + "\")";
        }
        String imported = firstNonNull(matcher.group(4), matcher.group(5));
        if (imported != null) {
            String replaced = replace.apply(imported);
            return replaced == null ? null : "@import \"" + replaced + "\"";
        }
        return null; // a comment
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * UTF-8 if the bytes are valid UTF-8, otherwise ISO-8859-1, which maps every byte to one character. Either way
     * decoding and encoding again gives back the original bytes, and the replacements are plain ASCII.
     */
    private static Charset charsetOf(byte[] css) {
        try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(css));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }
}
