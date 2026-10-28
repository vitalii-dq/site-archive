package org.archive.sitearchive.archive.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import org.archive.sitearchive.config.ArchiveProperties;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The page count must not exceed {@code archive.limits.max-pages-limit}. A plain {@code @Max} cannot be used
 * because the limit is configurable. {@code null} is valid.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = WithinMaxPagesLimit.Validator.class)
public @interface WithinMaxPagesLimit {

    String message() default "must not exceed the configured page limit";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Created by Spring, so the configured limit can be injected. */
    class Validator implements ConstraintValidator<WithinMaxPagesLimit, Integer> {

        private final int maxPagesLimit;

        public Validator(ArchiveProperties properties) {
            this.maxPagesLimit = properties.limits().maxPagesLimit();
        }

        @Override
        public boolean isValid(Integer maxPages, ConstraintValidatorContext context) {
            if (maxPages == null || maxPages <= maxPagesLimit) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("must not exceed " + maxPagesLimit).addConstraintViolation();
            return false;
        }
    }
}
