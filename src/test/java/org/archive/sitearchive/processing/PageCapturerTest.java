package org.archive.sitearchive.processing;

import org.archive.sitearchive.config.ArchiveProperties;
import org.archive.sitearchive.fetch.FetchException;
import org.archive.sitearchive.fetch.FetchedResponse;
import org.archive.sitearchive.fetch.HttpFetcher;
import org.archive.sitearchive.fetch.WebOrigin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PageCapturerTest {

    private static final URI URL = URI.create("https://example.org/post");
    private static final WebOrigin SITE = WebOrigin.parse("https://example.org");
    private static final Path FILE = Path.of("staged.tmp");
    private static final DataSize MAX_PAGE_SIZE = DataSize.ofKilobytes(5);

    private final HttpFetcher fetcher = mock(HttpFetcher.class);
    private final PageCapturer capturer = new PageCapturer(fetcher,
            new ArchiveProperties(null, new ArchiveProperties.Limits(1, 1, DataSize.ofMegabytes(1), MAX_PAGE_SIZE,
                    DataSize.ofMegabytes(1), 1, 0, Duration.ofSeconds(1), Duration.ofSeconds(1)), null, null, null));

    @ParameterizedTest
    @ValueSource(strings = {"text/html; charset=utf-8", "application/xhtml+xml"})
    @DisplayName("Streams an HTML page from the site into the file, within the page size limit")
    void capturesAnHtmlPage(String contentType) throws Exception {
        FetchedResponse<Path> response = serve(200, contentType);

        assertThat(capturer.capture(URL, SITE, FILE)).isSameAs(response);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"application/pdf, application/pdf", "null, none"})
    @DisplayName("Rejects a response that is not HTML for good, naming its content type")
    void rejectsAResponseThatIsNotHtml(String contentType, String shownContentType) throws Exception {
        serve(200, contentType);

        assertThatThrownBy(() -> capturer.capture(URL, SITE, FILE))
                .isInstanceOfSatisfying(FetchException.class, e -> {
                    assertThat(e).hasMessage("Not an HTML page (Content-Type: " + shownContentType + ")");
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.httpStatus()).isEqualTo(200);
                });
    }

    @Test
    @DisplayName("Fails an error response by its status before looking at its content type, so a server error "
            + "is retried")
    void failsAnErrorResponseByItsStatus() throws Exception {
        serve(503, "text/plain");

        assertThatThrownBy(() -> capturer.capture(URL, SITE, FILE))
                .isInstanceOfSatisfying(FetchException.class, e -> {
                    assertThat(e.isRetryable()).isTrue();
                    assertThat(e.httpStatus()).isEqualTo(503);
                });
    }

    private FetchedResponse<Path> serve(int status, String contentType) throws Exception {
        FetchedResponse<Path> response = new FetchedResponse<>(URL, status, contentType, status == 200 ? FILE : null);
        when(fetcher.fetchToFile(URL, MAX_PAGE_SIZE.toBytes(), SITE, FILE)).thenReturn(response);
        return response;
    }
}
