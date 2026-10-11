package com.slyph.cloverchat.feature.censor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CensorServiceTest {

    @Test
    void censorsConfiguredWordsCaseInsensitively() {
        assertEquals(
                "Это Д***К и д***к",
                CensorService.censorWithPatterns(
                        "Это ДУРАК и дурак",
                        CensorService.compilePatterns(List.of("дурак"))
                )
        );
    }

    @Test
    void censorsWordsSplitByColorCodes() {
        assertEquals(
                "д***к",
                CensorService.censorWithPatterns(
                        "д&cу&#FF0000рак",
                        CensorService.compilePatterns(List.of("дурак"))
                )
        );
    }

    @Test
    void doesNotCensorInsideAnotherWord() {
        assertEquals(
                "полудуракский",
                CensorService.censorWithPatterns(
                        "полудуракский",
                        CensorService.compilePatterns(List.of("дурак"))
                )
        );
    }
}
