package org.archive.sitearchive.archive.api.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.archive.sitearchive.archive.api.dto.CreateArchiveRequest;
import org.archive.sitearchive.config.ArchiveProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WithinMaxPagesLimitTest {

    private static final int LIMIT = 50;

    private static GenericApplicationContext context;
    private static Validator validator;

    /** The validator is created as in the application: by Spring, which injects the configured limit. */
    @BeforeAll
    static void createValidator() {
        context = new GenericApplicationContext();
        context.registerBean(ArchiveProperties.class, () -> new ArchiveProperties(null,
                new ArchiveProperties.Limits(10, LIMIT, DataSize.ofMegabytes(10), DataSize.ofMegabytes(5),
                        DataSize.ofMegabytes(25), 2000, 5, Duration.ofSeconds(5), Duration.ofSeconds(20)),
                null, null, null));
        context.registerBean(LocalValidatorFactoryBean.class);
        context.refresh();
        validator = context.getBean(LocalValidatorFactoryBean.class);
    }

    @AfterAll
    static void closeContext() {
        context.close();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = LIMIT)
    @DisplayName("Accepts no count, or a count up to the configured limit")
    void acceptsAnOptionalCountUpToTheLimit(Integer maxPages) {
        assertThat(violations(maxPages)).isEmpty();
    }

    @Test
    @DisplayName("Rejects a count over the configured limit, naming the limit")
    void rejectsACountOverTheLimitAndNamesTheLimit() {
        assertThat(violations(LIMIT + 1)).singleElement()
                .extracting(ConstraintViolation::getMessage)
                .isEqualTo("must not exceed " + LIMIT);
    }

    private static Set<ConstraintViolation<CreateArchiveRequest>> violations(Integer maxPages) {
        return validator.validateProperty(new CreateArchiveRequest("https://example.org/sitemap.xml", maxPages),
                "maxPages");
    }
}
