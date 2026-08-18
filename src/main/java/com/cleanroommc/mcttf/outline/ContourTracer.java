package com.cleanroommc.mcttf.outline;

import com.cleanroommc.mcttf.glyph.GlyphBuilder;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Traces the pixel-perfect (rectilinear) boundary of the opaque regions of a bitmap into closed polygon contours;
 * in pixel-grid coordinates (x right, y down, origin at top-left corner of pixel (0,0)).
 *
 * <p>Filled regions (8-connected, pixels touching only at a corner still count as one region,
 * e.g. the counter of a capital A) and holes (4-connected background pixels not
 * reachable from outside the bitmap, found via a padded flood fill) are separated first,
 * so there is never any ambiguity about what's inside what - unlike classifying loops
 * after the fact via point-in-polygon containment. Each filled region is traced forward,
 * each hole reversed, which is already the correct winding {@link GlyphBuilder}
 * expects (it flips Y into font space on emission).
 */
public final class ContourTracer {

    public static List<List<Point>> trace(boolean[][] pixels) {
        int h = pixels.length;
        int w = h == 0 ? 0 : pixels[0].length;

        List<List<Point>> result = new ArrayList<>();
        for (boolean[][] region : connectedComponents(pixels, w, h, true)) {
            result.add(simplify(traceRegionOutline(region, w, h)));
        }
        for (boolean[][] region : findHoles(pixels, w, h)) {
            List<Point> points = traceRegionOutline(region, w, h);
            Collections.reverse(points);
            result.add(simplify(points));
        }
        return result;
    }

    /**
     * Background pixels not reachable from outside the bitmap. Pads the inverted bitmap with a
     * 1px border of background first, so "outside" is always exactly one connected 4-connectivity
     * component reachable from the corner - everything else 4-connected-background is a real hole.
     */
    private static List<boolean[][]> findHoles(boolean[][] pixels, int w, int h) {
        int pw = w + 2, ph = h + 2;
        boolean[][] inverted = new boolean[ph][pw];
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                boolean sourceOpaque = y >= 1 && y <= h && x >= 1 && x <= w && pixels[y - 1][x - 1];
                inverted[y][x] = !sourceOpaque;
            }
        }
        List<boolean[][]> components = connectedComponents(inverted, pw, ph, false);
        // Row-major scan always finds the padding-connected "outside" component first, since (0,0)
        // is always background and the 1px border is a single ring fully joined by cardinal steps.
        List<boolean[][]> holes = new ArrayList<>();
        for (int i = 1; i < components.size(); i++) {
            boolean[][] padded = components.get(i);
            boolean[][] shifted = new boolean[h][w];
            for (int y = 0; y < h; y++) {
                System.arraycopy(padded[y + 1], 1, shifted[y], 0, w);
            }
            holes.add(shifted);
        }
        return holes;
    }

    private static List<boolean[][]> connectedComponents(boolean[][] grid, int w, int h, boolean eightConnected) {
        int[] dx = eightConnected ? new int[] { 1, -1, 0, 0, 1, 1, -1, -1 } : new int[] { 1, -1, 0, 0 };
        int[] dy = eightConnected ? new int[] { 0, 0, 1, -1, 1, -1, 1, -1 } : new int[] { 0, 0, 1, -1 };

        boolean[][] visited = new boolean[h][w];
        List<boolean[][]> components = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!grid[y][x] || visited[y][x]) continue;
                boolean[][] component = new boolean[h][w];
                Deque<int[]> stack = new ArrayDeque<>();
                stack.push(new int[]{x, y});
                visited[y][x] = true;
                while (!stack.isEmpty()) {
                    int[] p = stack.pop();
                    component[p[1]][p[0]] = true;
                    for (int d = 0; d < dx.length; d++) {
                        int nx = p[0] + dx[d], ny = p[1] + dy[d];
                        if (nx >= 0 && ny >= 0 && nx < w && ny < h && grid[ny][nx] && !visited[ny][nx]) {
                            visited[ny][nx] = true;
                            stack.push(new int[]{nx, ny});
                        }
                    }
                }
                components.add(component);
            }
        }
        return components;
    }

    /**
     * Walks the boundary of a single isolated region (a full-size grid with only that region's
     * pixels set) by inspecting the 4 pixels around each boundary vertex. The two "checkerboard"
     * cases - where a vertex touches two opaque pixels only diagonally - are resolved using the
     * direction just walked, which is the standard way to keep such a trace on one consistent side.
     */
    private static List<Point> traceRegionOutline(boolean[][] region, int w, int h) {
        Point start = findFirstOpaque(region, w, h);
        String facing = "right";
        Point pos = start;
        List<Point> result = new ArrayList<>();
        result.add(pos);
        do {
            int x = pos.x, y = pos.y;
            boolean topLeft = isSet(region, x - 1, y - 1, w, h);
            boolean topRight = isSet(region, x, y - 1, w, h);
            boolean bottomLeft = isSet(region, x - 1, y, w, h);
            boolean bottomRight = isSet(region, x, y, w, h);
            if (topLeft && bottomRight && !topRight && !bottomLeft) {
                if (facing.equals("up")) {
                    facing = "left";
                    pos = new Point(x - 1, y);
                } else {
                    facing = "right";
                    pos = new Point(x + 1, y);
                }
            } else if (topRight && bottomLeft && !topLeft && !bottomRight) {
                if (facing.equals("right")) {
                    facing = "up";
                    pos = new Point(x, y - 1);
                } else {
                    facing = "down";
                    pos = new Point(x, y + 1);
                }
            } else if (topLeft && !bottomLeft) {
                facing = "left";
                pos = new Point(x - 1, y);
            } else if (topRight && !topLeft) {
                facing = "up";
                pos = new Point(x, y - 1);
            } else if (bottomRight && !topRight) {
                facing = "right";
                pos = new Point(x + 1, y);
            } else if (bottomLeft && !bottomRight) {
                facing = "down";
                pos = new Point(x, y + 1);
            } else {
                throw new IllegalStateException("Cannot continue outline trace at " + pos);
            }
            result.add(pos);
        } while (!pos.equals(start));
        return result;
    }

    private static Point findFirstOpaque(boolean[][] region, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (region[y][x]) return new Point(x, y);
            }
        }
        throw new IllegalArgumentException("region has no opaque pixels");
    }

    private static boolean isSet(boolean[][] region, int x, int y, int w, int h) {
        if (x < 0 || y < 0 || x >= w || y >= h) {
            return false;
        }
        return region[y][x];
    }

    /** Collapse consecutive collinear points so long straight runs become a single segment. */
    private static List<Point> simplify(List<Point> loop) {
        // Loop is closed with the start point repeated at the end; drop that duplicate first
        List<Point> open = loop.subList(0, loop.size() - 1);
        int n = open.size();
        if (n < 3) {
            return open;
        }
        List<Point> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Point prev = open.get((i - 1 + n) % n);
            Point cur = open.get(i);
            Point next = open.get((i + 1) % n);
            long dx1 = cur.x - prev.x, dy1 = cur.y - prev.y;
            long dx2 = next.x - cur.x, dy2 = next.y - cur.y;
            boolean collinear = dx1 * dy2 - dy1 * dx2 == 0;
            if (!collinear) {
                result.add(cur);
            }
        }
        return result.isEmpty() ? open : result;
    }

    private ContourTracer() { }

}
