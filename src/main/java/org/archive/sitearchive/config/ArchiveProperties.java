package org.archive.sitearchive.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;

/** All application settings under the {@code archive} prefix; defaults live in application.yml. */
@Validated
@ConfigurationProperties("archive")
public record ArchiveProperties(
        @Valid @NotNull Storage storage,
        @Valid @NotNull Limits limits,
        @Valid @NotNull Fetch fetch,
        @Valid @NotNull Retry retry,
        @Valid @NotNull Worker worker) {

    public record Storage(@NotNull Path root) {
    }

    public record Limits(
            @Positive int defaultMaxPages,
            @Positive int maxPagesLimit,
            @NotNull DataSize maxSitemapSize,
            @NotNull DataSize maxPageSize,
            @NotNull DataSize maxAssetSize,
            @Positive int maxAssetsPerJob,
            @PositiveOrZero int maxRedirects,
            @NotNull Duration connectTimeout,
            @NotNull Duration requestTimeout) {

        public Limits {
            if (defaultMaxPages > maxPagesLimit) {
                throw new IllegalArgumentException("archive.limits.default-max-pages must not exceed max-pages-limit");
            }
        }
    }

    public record Fetch(@NotBlank String userAgent) {
    }

    public record Retry(
            @Min(1) int maxAttempts,
            @NotNull Duration initialBackoff,
            @DecimalMin("1.0") double multiplier,
            @NotNull Duration maxBackoff) {
    }

    public record Worker(@Positive int concurrency, @NotNull Duration pollInterval, @NotNull Duration errorPause) {
    }
}
