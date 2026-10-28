package org.archive.sitearchive.assets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceResolverTest {

    private static final Map<String, String> COPIES = Map.of("https://example.org/img/a.png", "../assets/1-a.png");
    private static final ReferenceResolver RESOLVER =
            new ReferenceResolver(URI.create("https://example.org/blog/post.html"), url -> COPIES.get(url.toString()));

    @ParameterizedTest
    @CsvSource({
            "/img/a.png,                         ../assets/1-a.png",
            "'  ../img/a.png  ',                 ../assets/1-a.png",
            "HTTPS://Example.ORG:443/img/a.png,  ../assets/1-a.png",
            "/img/a.png#part,                    ../assets/1-a.png#part",
            "/img/a.png?v=2,                     https://example.org/img/a.png?v=2",
            "other.png,                          https://example.org/blog/other.png",
            "//cdn.example.com/x.js,             https://cdn.example.com/x.js",
            "/img/café.png,                      https://example.org/img/caf%C3%A9.png"
    })
    @DisplayName("Uses the local copy of the canonical URL, keeping the fragment, and otherwise makes relative "
            + "references absolute")
    void replacesTheReference(String reference, String replacement) {
        assertThat(RESOLVER.apply(reference)).isEqualTo(replacement);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "#top", "https://cdn.example.com/x.js", "data:image/png;base64,AAAA",
            "mailto:someone@example.org", "javascript:void(0)", "a b.png"})
    @DisplayName("Keeps empty and fragment-only references, absolute ones without a local copy, other schemes and "
            + "invalid syntax")
    void keepsTheReference(String reference) {
        assertThat(RESOLVER.apply(reference)).isNull();
    }
}
