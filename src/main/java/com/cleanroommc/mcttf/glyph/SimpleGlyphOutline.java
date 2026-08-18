package com.cleanroommc.mcttf.glyph;

import java.awt.Point;
import java.util.List;

/** A glyph's outline already converted to font-unit space, ready to be serialized into a {@code glyph} entry. */
public record SimpleGlyphOutline(List<List<Point>> contours, int xMin, int yMin, int xMax, int yMax, int advanceWidth, int leftSideBearing) {

    public static SimpleGlyphOutline empty(int advanceWidth) {
        return new SimpleGlyphOutline(List.of(), 0, 0, 0, 0, advanceWidth, 0);
    }

    public int pointCount() {
        return contours.stream().mapToInt(List::size).sum();
    }

}
