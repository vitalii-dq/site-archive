package org.archive.sitearchive.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class WebOriginTest {

    private static final WebOrigin ORIGIN = new WebOrigin("https", "example.org", 443);

    @ParameterizedTest
    @CsvSource({
            "HTTPS://Example.ORG/path?q=1, https, example.org, 443, https://example.org",
            "http://example.org/,          http,  example.org, 80,  http://example.org",
            "http://example.org:8080/,     http,  example.org, 8080, http://example.org:8080"
    })
    @DisplayName("Lower-cases scheme and host, fills in the default port and omits it again when printed")
    void takesTheOriginOfAUrl(String url, String scheme, String host, int port, String printed) {
        WebOrigin origin = WebOrigin.of(URI.create(url));

        assertThat(origin).isEqualTo(new WebOrigin(scheme, host, port));
        assertThat(origin).hasToString(printed);
        assertThat(WebOrigin.parse(printed)).isEqualTo(origin);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/relative", "mailto:someone@example.org"})
    @DisplayName("A URL without scheme or host has no origin")
    void rejectsAUrlWithoutOrigin(String url) {
        assertThatIllegalArgumentException().isThrownBy(() -> WebOrigin.of(URI.create(url)));
    }

    @ParameterizedTest
    @CsvSource({
            "https://example.org/a,        true",
            "http://example.org/,          false",
            "https://example.org:8443/,    false",
            "https://www.example.org/,     false",
            "/relative,                    false",
            "mailto:someone@example.org,   false"
    })
    @DisplayName("Contains only URLs with the same scheme, host and port")
    void containsUrlsOfTheSameOrigin(String url, boolean contained) {
        assertThat(ORIGIN.contains(URI.create(url))).isEqualTo(contained);
    }
}
