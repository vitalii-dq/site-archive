package org.archive.sitearchive.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MediaTypesTest {

    private static final List<MediaType> HTML = List.of(MediaType.TEXT_HTML, MediaType.APPLICATION_XHTML_XML);

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "TEXT/HTML", "text/html; charset=utf-8", "application/xhtml+xml"})
    @DisplayName("Matches type and subtype case-insensitively, ignoring parameters")
    void matchesTheMediaType(String contentType) {
        assertThat(MediaTypes.matches(contentType, HTML)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"text/plain", "text/*", "not a type"})
    @DisplayName("Does not match a missing, different, wildcard or invalid Content-Type")
    void doesNotMatchOtherContentTypes(String contentType) {
        assertThat(MediaTypes.matches(contentType, HTML)).isFalse();
    }
}
