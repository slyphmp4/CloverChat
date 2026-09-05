package com.slyph.cloverchat.feature.messagestyle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GradientColorInterpolatorTest {

    @Test
    void interpolatesBetweenTwoColors() {
        List<Integer> colors = List.of(0x000000, 0xFFFFFF);

        assertEquals(0x000000, GradientColorInterpolator.interpolate(colors, 0, 3));
        assertEquals(0x808080, GradientColorInterpolator.interpolate(colors, 1, 3));
        assertEquals(0xFFFFFF, GradientColorInterpolator.interpolate(colors, 2, 3));
    }

    @Test
    void passesThroughEveryConfiguredColorStop() {
        List<Integer> colors = List.of(0xFF0000, 0x00FF00, 0x0000FF);

        assertEquals(0xFF0000, GradientColorInterpolator.interpolate(colors, 0, 5));
        assertEquals(0x00FF00, GradientColorInterpolator.interpolate(colors, 2, 5));
        assertEquals(0x0000FF, GradientColorInterpolator.interpolate(colors, 4, 5));
    }
}
