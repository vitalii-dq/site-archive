package org.archive.sitearchive.archive.api.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.archive.sitearchive.archive.api.dto.CreateArchiveRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Which URLs are valid is up to {@code HttpUrls.parse}, see {@code HttpUrlsTest}; this covers the constraint itself. */
class HttpUrlTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("Accepts a URL that HttpUrls can parse")
    void acceptsAFetchableUrl() {
        assertThat(violations("https://example.org/sitemap.xml")).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "ftp://example.org/sitemap.xml")
    @DisplayName("Rejects a missing or unparseable URL with one violation that explains the rule")
    void rejectsEverythingElseWithOneMessage(String url) {
        assertThat(violations(url)).singleElement()
                .extracting(ConstraintViolation::getMessage)
                .asString()
                .startsWith("must be an absolute http or https URL");
    }

    /** Only the sitemapUrl constraints: the maxPages one needs the application's configuration. */
    private static Set<ConstraintViolation<CreateArchiveRequest>> violations(String url) {
        return VALIDATOR.validateProperty(new CreateArchiveRequest(url, null), "sitemapUrl");
    }
}
