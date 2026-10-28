package org.archive.sitearchive.fetch;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
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
    void returnsTheCanonicalForm(String value, String canonical) {
        assertThat(HttpUrls.parse(value)).contains(URI.create(canonical));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "ftp://example.org/", "http://user@example.org/", "http:///path", "/relative",
            "mailto:someone@example.org", "http://exa mple.org/"})
    void rejectsAnythingElse(String value) {
        assertThat(HttpUrls.parse(value)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {2048, 2049})
    void limitsTheLength(int length) {
        String url = "http://example.org/" + "a".repeat(length - "http://example.org/".length());

        assertThat(HttpUrls.parse(url).isPresent()).isEqualTo(length <= HttpUrls.MAX_LENGTH);
    }
}
