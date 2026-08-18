package com.cleanroommc.mcttf.testsupport;

import java.awt.Point;
import java.util.List;

/**
 * Rasterizes traced/emitted glyph contours back to a yes/no fill test using the nonzero
 * winding rule - the same fill rule TrueType scan conversion uses - so tests can assert
 * a vector trace reproduces its source bitmap exactly, pixel by pixel.
 */
public final class WindingRasterizer {

    private WindingRasterizer() {
    }

    public static boolean filled(List<List<Point>> contours, double px, double py) {
        int winding = 0;
        for (List<Point> contour : contours) {
            int n = contour.size();
            for (int i = 0; i < n; i++) {
                Point a = contour.get(i);
                Point b = contour.get((i + 1) % n);
                if (a.y <= py) {
                    if (b.y > py && cross(a, b, px, py) > 0) winding++;
                } else {
                    if (b.y <= py && cross(a, b, px, py) < 0) winding--;
                }
            }
        }
        return winding != 0;
    }

    private static double cross(Point a, Point b, double px, double py) {
        return (double) (b.x - a.x) * (py - a.y) - (px - a.x) * (b.y - a.y);
    }
}
