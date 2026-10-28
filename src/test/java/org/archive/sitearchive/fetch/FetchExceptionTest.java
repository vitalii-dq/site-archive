package org.archive.sitearchive.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class FetchExceptionTest {

    @ParameterizedTest
    @CsvSource({
            "429, true",
            "500, true",
            "503, true",
            "400, false",
            "403, false",
            "404, false"
    })
    @DisplayName("Rate limiting and server errors are retryable, other error statuses are not")
    void classifiesHttpStatuses(int status, boolean retryable) {
        FetchException exception = FetchException.forHttpStatus(status);

        assertThat(exception.isRetryable()).isEqualTo(retryable);
        assertThat(exception.httpStatus()).isEqualTo(status);
        assertThat(exception).hasMessage("HTTP status " + status);
    }
}
