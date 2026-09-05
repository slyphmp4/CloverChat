package com.slyph.cloverchat.feature.messagestyle;

import java.util.List;

final class GradientColorInterpolator {

    private GradientColorInterpolator() {
    }

    static int interpolate(List<Integer> colors, int position, int totalLength) {
        if (colors == null || colors.isEmpty()) {
            return 0xFFFFFF;
        }
        if (colors.size() == 1 || totalLength <= 1) {
            return colors.get(0);
        }

        int boundedPosition = Math.max(0, Math.min(position, totalLength - 1));
        double progress = (double) boundedPosition / (double) (totalLength - 1);
        double scaled = progress * (colors.size() - 1);
        int segment = Math.min((int) Math.floor(scaled), colors.size() - 2);
        double segmentProgress = scaled - segment;

        int start = colors.get(segment);
        int end = colors.get(segment + 1);
        int red = interpolateChannel((start >> 16) & 0xFF, (end >> 16) & 0xFF, segmentProgress);
        int green = interpolateChannel((start >> 8) & 0xFF, (end >> 8) & 0xFF, segmentProgress);
        int blue = interpolateChannel(start & 0xFF, end & 0xFF, segmentProgress);
        return (red << 16) | (green << 8) | blue;
    }

    private static int interpolateChannel(int start, int end, double progress) {
        return (int) Math.round(start + (end - start) * progress);
    }
}
