package codebridge;

import core.game.node.scenery.Scenery;
import core.game.world.map.Region;
import core.game.world.map.RegionManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Long walks. The game's own pathfinder only looks about 50 tiles around the character, so a walk across town
 * (out of a castle, round a river) needs a route first. This searches the map's walking rules over the whole area
 * between here and there, treating closed doors and gates as open (the walker opens them on the way).
 */
final class Router {
    private Router() {}

    private static final int MARGIN = 48, MAX_SIDE = 900;
    // the game's own walking rules (SmartPathfinder): what stops a step into a tile from each side
    private static final int S = 0x12c0102, W = 0x12c0108, N = 0x12c0120, E = 0x12c0180,
        SW = 0x12c010e, NW = 0x12c0138, SE = 0x12c0183, NE = 0x12c01e0;

    /** Tiles from (sx,sy) towards (tx,ty) on floor z: to the target, or to the reachable tile nearest it. Empty if none. */
    static List<int[]> route(int sx, int sy, int tx, int ty, int z, int agility) {
        List<int[]> r = route(sx, sy, tx, ty, z, MARGIN, agility);
        // the way round can leave the box between here and there (Catherby from the south goes round White Wolf
        // Mountain): if it falls short, search a much wider area before giving up
        if (gap(r, tx, ty) > 3) {
            List<int[]> wide = route(sx, sy, tx, ty, z, WIDE_MARGIN, agility);
            if (gap(wide, tx, ty) < gap(r, tx, ty) - 1) r = wide;
        }
        return r;
    }

    private static final int WIDE_MARGIN = 220;

    private static double gap(List<int[]> r, int tx, int ty) {
        if (r.isEmpty()) return Double.MAX_VALUE;
        int[] end = r.get(r.size() - 1);
        return Math.hypot(end[0] - tx, end[1] - ty);
    }

    private static List<int[]> route(int sx, int sy, int tx, int ty, int z, int margin, int agility) {
        int x0 = Math.min(sx, tx) - margin, y0 = Math.min(sy, ty) - margin;
        int w = Math.min(MAX_SIDE, Math.abs(sx - tx) + 2 * margin + 1), h = Math.min(MAX_SIDE, Math.abs(sy - ty) + 2 * margin + 1);
        if (sx - x0 >= w || sy - y0 >= h) return new ArrayList<>();
        int[] flag = new int[w * h];
        for (int i = 0; i < w; i++) for (int j = 0; j < h; j++) {
            int f;
            try { f = RegionManager.getClippingFlag(z, x0 + i, y0 + j); } catch (Throwable e) { f = -1; }
            flag[i * h + j] = f;
        }
        openDoors(flag, x0, y0, w, h, z, agility);

        int[] from = new int[w * h];
        java.util.Arrays.fill(from, -1);
        int[] queue = new int[w * h];
        int head = 0, tail = 0, start = (sx - x0) * h + (sy - y0);
        from[start] = start;
        queue[tail++] = start;
        int best = start;
        double bestD = Math.hypot(sx - tx, sy - ty);
        int goalI = tx - x0, goalJ = ty - y0;
        while (head < tail) {
            int c = queue[head++];
            int i = c / h, j = c % h;
            double d = Math.hypot(i - goalI, j - goalJ);
            if (d < bestD) { bestD = d; best = c; if (d == 0) break; }
            // straight steps
            if (j > 0 && ok(flag, i, j - 1, h, S)) tail = add(from, queue, tail, c, i * h + j - 1);
            if (i > 0 && ok(flag, i - 1, j, h, W)) tail = add(from, queue, tail, c, (i - 1) * h + j);
            if (j < h - 1 && ok(flag, i, j + 1, h, N)) tail = add(from, queue, tail, c, i * h + j + 1);
            if (i < w - 1 && ok(flag, i + 1, j, h, E)) tail = add(from, queue, tail, c, (i + 1) * h + j);
            // diagonal steps need both sides clear too
            if (i > 0 && j > 0 && ok(flag, i - 1, j - 1, h, SW) && ok(flag, i - 1, j, h, W) && ok(flag, i, j - 1, h, S))
                tail = add(from, queue, tail, c, (i - 1) * h + j - 1);
            if (i > 0 && j < h - 1 && ok(flag, i - 1, j + 1, h, NW) && ok(flag, i - 1, j, h, W) && ok(flag, i, j + 1, h, N))
                tail = add(from, queue, tail, c, (i - 1) * h + j + 1);
            if (i < w - 1 && j > 0 && ok(flag, i + 1, j - 1, h, SE) && ok(flag, i + 1, j, h, E) && ok(flag, i, j - 1, h, S))
                tail = add(from, queue, tail, c, (i + 1) * h + j - 1);
            if (i < w - 1 && j < h - 1 && ok(flag, i + 1, j + 1, h, NE) && ok(flag, i + 1, j, h, E) && ok(flag, i, j + 1, h, N))
                tail = add(from, queue, tail, c, (i + 1) * h + j + 1);
        }
        List<int[]> path = new ArrayList<>();
        for (int c = best; ; c = from[c]) {
            path.add(0, new int[]{x0 + c / h, y0 + c % h});
            if (c == start) break;
        }
        return path;
    }

    /**
     * Test only: which of these points can be walked to from (sx, sy) on the surface, with doors opened on the way.
     * One flood over the whole box; a point counts when a reached tile is within `near` tiles of it. Returns "1"/"0" per point.
     */
    static String reach(int sx, int sy, int[][] pts, int near, int x0, int y0, int w, int h) {
        int[] flag = new int[w * h];
        for (int i = 0; i < w; i++) for (int j = 0; j < h; j++) {
            int f;
            try { f = RegionManager.getClippingFlag(0, x0 + i, y0 + j); } catch (Throwable e) { f = -1; }
            flag[i * h + j] = f;
        }
        openDoors(flag, x0, y0, w, h, 0, 99);
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[w * h];
        int head = 0, tail = 0;
        seen[(sx - x0) * h + (sy - y0)] = true;
        queue[tail++] = (sx - x0) * h + (sy - y0);
        while (head < tail) {
            int c = queue[head++], i = c / h, j = c % h;
            int[][] steps = {{0, -1, S}, {-1, 0, W}, {0, 1, N}, {1, 0, E}};
            for (int[] st : steps) {
                int ni = i + st[0], nj = j + st[1];
                if (ni < 0 || nj < 0 || ni >= w || nj >= h || seen[ni * h + nj] || !ok(flag, ni, nj, h, st[2])) continue;
                seen[ni * h + nj] = true;
                queue[tail++] = ni * h + nj;
            }
        }
        // per point: the nearest tile that can be walked to, "x,y", or "-" when none is within `near`
        StringBuilder out = new StringBuilder();
        for (int[] p : pts) {
            int bx = 0, by = 0; double bd = Double.MAX_VALUE;
            for (int dx = -near; dx <= near; dx++) for (int dy = -near; dy <= near; dy++) {
                int i = p[0] + dx - x0, j = p[1] + dy - y0;
                double d = Math.hypot(dx, dy);
                if (i >= 0 && j >= 0 && i < w && j < h && seen[i * h + j] && d < bd) { bd = d; bx = p[0] + dx; by = p[1] + dy; }
            }
            out.append(out.length() > 0 ? " " : "").append(bd == Double.MAX_VALUE ? "-" : bx + "," + by);
        }
        return out.toString();
    }

    private static boolean ok(int[] flag, int i, int j, int h, int mask) {
        int f = flag[i * h + j];
        return f != -1 && (f & mask) == 0;
    }

    private static int add(int[] from, int[] queue, int tail, int c, int n) {
        if (from[n] != -1) return tail;
        from[n] = c;
        queue[tail] = n;
        return tail + 1;
    }

    /**
     * Clears the blocking at doors, gates, stiles, ditches and passes (and the tiles beside them), so the route can go
     * through. Agility shortcuts count only when the player has the level for them; otherwise the route goes round.
     */
    private static void openDoors(int[] flag, int x0, int y0, int w, int h, int z, int agility) {
        for (int rx = x0 >> 6; rx <= (x0 + w) >> 6; rx++) for (int ry = y0 >> 6; ry <= (y0 + h) >> 6; ry++) {
            Region r;
            try { r = RegionManager.forId((rx << 8) | ry); Region.load(r); } catch (Throwable e) { continue; }
            if (r == null) continue;
            for (Scenery s : r.getPlanes()[z].getObjectList()) {
                if (s == null || s.getName() == null) continue;
                if (GameApi.passageOption(s, agility) == null) continue;
                int sx = s.getLocation().getX() - x0, sy = s.getLocation().getY() - y0;
                // the doorway itself (its walls and the object), and on each side only the wall that faces it:
                // clearing whole neighbour tiles let routes slip through the wall beside a door
                int[][] around = {{0, 0, 0x2001ff}, {1, 0, 0xc1}, {-1, 0, 0x1c}, {0, 1, 0x70}, {0, -1, 0x07}};
                for (int[] a : around) {
                    int i = sx + a[0], j = sy + a[1];
                    if (i < 0 || j < 0 || i >= w || j >= h || flag[i * h + j] == -1) continue;
                    flag[i * h + j] &= ~a[2];
                }
                // a big gate (the Gnome Stronghold's is 5 tiles wide): the whole of it
                int bx = Math.max(1, s.getSizeX()), by = Math.max(1, s.getSizeY());
                if (bx > 1 || by > 1) for (int dx = 0; dx < bx; dx++) for (int dy = 0; dy < by; dy++) {
                    int i = sx + dx, j = sy + dy;
                    if (i < 0 || j < 0 || i >= w || j >= h || flag[i * h + j] == -1) continue;
                    flag[i * h + j] &= ~0x2001ff;
                }
            }
        }
    }
}
