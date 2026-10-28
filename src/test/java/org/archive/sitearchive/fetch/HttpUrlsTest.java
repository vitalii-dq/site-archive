package org.archive.sitearchive.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class HttpUrlsTest {

    @ParameterizedTest
    @CsvSource({
            "HTTP://Example.ORG,                        http://example.org/",
            "http://example.org:80/a,                   http://example.org/a",
            "https://example.org:443?q=1,               https://example.org/?q=1",
            "https://example.org:8443/A%20b?x=%2F#frag, https://example.org:8443/A%20b?x=%2F",
            "'  http://example.org/p  ',                http://example.org/p",
            "http://example.org:443/,                   http://example.org:443/"
    })
    @DisplayName("Canonical form: lower-case scheme and host, no default port or fragment, '/' for an empty path, "
            + "path and query as written")
    void returnsTheCanonicalForm(String value, String canonical) {
        assertThat(HttpUrls.parse(value)).contains(URI.create(canonical));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"   ", "ftp://example.org/", "/relative", "http:///path", "http://user@example.org/",
            "http://exa mple.org/"})
    @DisplayName("Rejects a missing value, another or no scheme, no host, credentials and invalid syntax")
    void rejectsAnythingElse(String value) {
        assertThat(HttpUrls.parse(value)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {2048, 2049})
    @DisplayName("Accepts URLs of up to 2048 characters and rejects longer ones")
    void limitsTheLength(int length) {
        String url = "http://example.org/" + "a".repeat(length - "http://example.org/".length());

        assertThat(HttpUrls.parse(url).isPresent()).isEqualTo(length <= HttpUrls.MAX_LENGTH);
    }

    @ParameterizedTest
    @CsvSource({"2047, true", "2048, false"})
    @DisplayName("Measures the canonical form too, which is one longer when '/' is added for an empty path")
    void limitsTheLengthOfTheCanonicalForm(int length, boolean accepted) {
        String url = "http://example.org?" + "a".repeat(length - "http://example.org?".length());

        assertThat(HttpUrls.parse(url).isPresent()).isEqualTo(accepted);
    }

    @ParameterizedTest
    @CsvSource({
            "?page=2,       https://example.org/blog/post.html?page=2",
            "?page=2#top,   https://example.org/blog/post.html?page=2#top",
            "other.html?a,  https://example.org/blog/other.html?a",
            "/x?a,          https://example.org/x?a",
            "#top,          https://example.org/blog/post.html?old=1#top",
            "//cdn.test/a,  https://cdn.test/a"
    })
    @DisplayName("Resolves as RFC 3986 does, keeping the base path for a reference that is only a query")
    void resolvesAsRfc3986Does(String reference, String resolved) {
        URI base = URI.create("https://example.org/blog/post.html?old=1");

        assertThat(HttpUrls.resolve(base, URI.create(reference))).isEqualTo(URI.create(resolved));
    }

    @Test
    @DisplayName("Measures the length after removing surrounding whitespace")
    void doesNotCountSurroundingWhitespaceTowardsTheLength() {
        String url = "http://example.org/" + "a".repeat(HttpUrls.MAX_LENGTH - "http://example.org/".length());

        assertThat(HttpUrls.parse("  " + url + "\n")).contains(URI.create(url));
    }
}
