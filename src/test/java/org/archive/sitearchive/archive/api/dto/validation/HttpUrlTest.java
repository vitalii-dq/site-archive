package org.archive.sitearchive.archive.api.dto.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.archive.sitearchive.archive.api.dto.CreateArchiveRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HttpUrlTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @ValueSource(strings = {"https://example.org/sitemap.xml", "HTTP://Example.ORG:8080/a?b#c"})
    void acceptsFetchableUrls(String url) {
        assertThat(violations(url)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "ftp://example.org/x", "https://user:pw@example.org/x", "/sitemap.xml"})
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
