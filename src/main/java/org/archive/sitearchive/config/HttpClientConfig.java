package org.archive.sitearchive.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

@Configuration
class HttpClientConfig {

    @Bean
    HttpClient archiveHttpClient(ArchiveProperties properties) {
        return HttpClient.newBuilder()
                // Redirects are followed manually so that every hop is checked against the site origin.
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(properties.limits().connectTimeout())
                .build();
    }
}
