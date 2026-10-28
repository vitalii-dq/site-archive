package org.archive.sitearchive.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FetchedResponseTest {

    private static final URI URL = URI.create("https://example.org/");

    @ParameterizedTest
    @ValueSource(ints = {200, 204, 299})
    @DisplayName("A successful response passes the success check unchanged")
    void passesASuccessfulResponse(int status) throws FetchException {
        FetchedResponse<String> response = new FetchedResponse<>(URL, status, null, "body");

        assertThat(response.requireSuccess()).isSameAs(response);
    }

    @Test
    @DisplayName("Any other response fails the success check with its status")
    void failsAnUnsuccessfulResponse() {
        FetchedResponse<String> response = new FetchedResponse<>(URL, 404, "text/html", null);

        assertThatThrownBy(response::requireSuccess)
                .isInstanceOfSatisfying(FetchException.class, e -> assertThat(e.httpStatus()).isEqualTo(404));
    }
}
