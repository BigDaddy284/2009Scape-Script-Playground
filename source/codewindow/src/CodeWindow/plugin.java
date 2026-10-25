package CodeWindow;

import plugin.Plugin;
import plugin.PluginRepository;
import plugin.api.API;
import plugin.api.WindowMode;
import plugin.api.FontColor;
import plugin.api.FontType;
import plugin.api.TextModifier;
import rt4.DisplayMode;
import rt4.GameShell;
import rt4.SoftwareRaster;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Code orb: a round button by the minimap that opens the Script Playground (a separate window run by the launcher).
 * Fixed layout: left of the minimap, between the compass and the world map orb.
 * Resizable layout: directly under the world map orb.
 * Also switches the game to resizable mode after login so it fills the window (::autoresize toggles this), and keeps
 * HD and the game's own settings (brightness, volumes, roofs, detail options) saved between launches.
 */
public class plugin extends Plugin {
    static final int R = 18;                 // outer radius, same as the compass / world map orbs
    private int cx = -100, cy = -100;        // orb centre on the canvas
    private boolean resizeDone;
    private int framesInGame;
    private static final int PORT = Integer.getInteger("codebridge.port", 43610);
    private Socket link;
    private BufferedReader linkIn;
    private PrintWriter linkOut;

    @Override public void Init() {
        restoreSettings();
        Thread beat = new Thread(this::heartbeat, "codewindow-heartbeat");
        beat.setDaemon(true);
        beat.start();
        API.AddMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (grabbing && inGame()) { swallowClick(e); grabClick(e); return; }
                if (inGame() && inside(e.getX(), e.getY())) { swallowClick(e); openWindow(); }
            }
            @Override public void mouseReleased(MouseEvent e) { if (grabbing || swallowRelease) { swallowRelease = false; e.consume(); } }
            @Override public void mouseClicked(MouseEvent e) { if (grabbing) e.consume(); }
        });
        API.AddKeyboardListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(java.awt.event.KeyEvent e) {
                boolean paste = e.getKeyCode() == java.awt.event.KeyEvent.VK_V && (e.isControlDown() || e.isMetaDown())
                    || e.getKeyCode() == java.awt.event.KeyEvent.VK_INSERT && e.isShiftDown();
                if (paste) paste();
                if (grabbing && e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE) { e.consume(); stopGrab(null); }
            }
        });
    }

    /** Keeps a click for the plugin: the game's own mouse listener has already seen it, so take it back before the next frame reads it. */
    private static void swallowClick(MouseEvent e) {
        e.consume();
        rt4.Mouse m = rt4.Mouse.instance;
        if (m == null) return;
        synchronized (m) { rt4.Mouse.anInt1313 = 0; rt4.Mouse.anInt1759 = 0; }
    }

    /** Ctrl+V (or Shift+Insert): types the clipboard into whatever box has the cursor, like chat or a cheat. */
    private static void paste() {
        String text;
        try {
            Object v = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            text = v == null ? "" : v.toString();
        } catch (Exception ex) { return; }
        int nl = text.indexOf('\n');
        if (nl >= 0) text = text.substring(0, nl);               // one line only: Enter is still yours to press
        text = text.replace("\r", "").replace('\t', ' ').trim();
        if (text.length() > 80) text = text.substring(0, 80);    // the chat box holds 80 characters
        rt4.Keyboard k = rt4.Keyboard.instance;
        if (k == null || GameShell.canvas == null) return;
        for (char c : text.toCharArray()) {
            if (c < 32 || c > 255) continue;                     // the game's font has no other characters
            k.keyTyped(new java.awt.event.KeyEvent(GameShell.canvas, java.awt.event.KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0,
                java.awt.event.KeyEvent.VK_UNDEFINED, c));
        }
    }

    @Override public void OnLogin() { resizeDone = false; framesInGame = 0; }

    @Override public void ProcessCommand(String cmd, String[] args) {
        if (cmd.equalsIgnoreCase("::code")) openWindow();
        if (cmd.equalsIgnoreCase("::autoresize")) {
            boolean off = Boolean.TRUE.equals(API.GetData("codewindow-no-autoresize"));
            API.StoreData("codewindow-no-autoresize", !off);
            API.SendMessage("Auto resize is now " + (off ? "on" : "off") + ".");
        }
    }

    private void place() {
        Dimension d = API.GetWindowDimensions();
        if (API.GetWindowMode() == WindowMode.FIXED) { cx = 543; cy = 82; }
        else { cx = d.width - 23; cy = 188; }   // directly under the world map orb
    }

    /** Standard detail: drawn after the whole frame, on top of everything, then copied to the screen. */
    /** Only on the game screen (fixed 548 or resizable 746), not on the welcome screen. */
    private static boolean inGame() {
        int top = rt4.InterfaceList.topLevelInterface;
        if (top != lastTop) { lastTop = top; System.out.println("[CodeWindow] top-level interface " + top); }
        return API.IsLoggedIn() && (top == 548 || top == 746);
    }
    private static int lastTop = -2;

    @Override public void LateDraw(long timeDelta) {
        rememberSettings();
        if (!inGame() || API.IsHD()) return;
        autoResize();
        place();
        Dimension d = API.GetWindowDimensions();
        int[] saved = new int[4];
        SoftwareRaster.saveClip(saved);
        SoftwareRaster.setClip(0, 0, d.width, d.height);
        drawOrb(cx, cy, inside(API.GetMouseX(), API.GetMouseY()));
        java.awt.Rectangle grab = drawGrabber();
        SoftwareRaster.restoreClip(saved);
        try {
            Graphics g = GameShell.canvas.getGraphics();
            SoftwareRaster.frameBuffer.drawAt(2 * R + 2, cx - R - 1, 2 * R + 2, g, cy - R - 1);
            if (grab != null) {
                grab = grab.intersection(new java.awt.Rectangle(0, 0, d.width, d.height));
                if (!grab.isEmpty()) SoftwareRaster.frameBuffer.drawAt(grab.width, grab.x, grab.height, g, grab.y);
            }
        } catch (Exception ignored) {}
    }

    /** High detail (OpenGL): drawn during the frame instead. */
    @Override public void Draw(long timeDelta) {
        if (!inGame() || !API.IsHD()) return;
        autoResize();
        place();
        drawOrb(cx, cy, inside(API.GetMouseX(), API.GetMouseY()));
        drawGrabber();
    }

    // ---- coordinate grabber: the Playground turns it on; the map coordinates of the tile under the mouse show
    // below the pointer, and a left click copies them (right-click or Esc cancels) ----
    private static volatile boolean grabbing;
    private static boolean swallowRelease;
    private static volatile long grabStopped;   // a poll answered before the stop reached the backend must not turn it back on
    private static java.awt.Cursor pickerCursor;
    private static boolean cursorSet;
    private int[] hoverTile;   // world x, y, floor under the mouse, or null

    /** Draws the highlighted tile and its coordinates; returns the area drawn on (null when nothing was). */
    private java.awt.Rectangle drawGrabber() {
        updateCursor();
        if (!grabbing) { hoverTile = null; return null; }
        int mx = API.GetMouseX(), my = API.GetMouseY();
        java.awt.Rectangle v = view();
        if (v != null && !v.contains(mx, my)) { hoverTile = null; return null; }
        int[][] quad = new int[4][];
        int[] t = tileAt(mx, my, quad);
        hoverTile = t;
        if (t == null) return null;
        java.awt.Rectangle area = null;
        for (int i = 0; i < 4; i++) {
            int[] a = quad[i], b = quad[(i + 1) % 4];
            line(a[0], a[1], b[0], b[1], 0xffff00);
            java.awt.Rectangle r = new java.awt.Rectangle(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.abs(a[0] - b[0]) + 1, Math.abs(a[1] - b[1]) + 1);
            area = area == null ? r : area.union(r);
        }
        String text = coords(t);
        int tw = text.length() * 7 + 10, tx = mx - tw / 2, ty = my + 26;
        if (v != null) {   // keep the label inside the 3D view
            if (ty + 5 > v.y + v.height) ty = my - 12;
            tx = Math.max(v.x, Math.min(v.x + v.width - tw, tx));
        }
        int cx = tx + tw / 2;
        API.FillRect(tx, ty - 13, tw, 18, 0x000000, 160);
        API.DrawText(FontType.SMALL, FontColor.YELLOW, TextModifier.CENTER, text, cx, ty);
        return area.union(new java.awt.Rectangle(tx, ty - 14, tw + 1, 20));
    }

    static String coords(int[] t) { return t[0] + ", " + t[1] + (t[2] == 0 ? "" : ", " + t[2]); }

    /**
     * The map tile under a point on the screen, or null: each tile near the player is projected to the screen and the
     * one whose outline holds the point wins. quad gets that outline's four corners.
     */
    private static int[] tileAt(int mx, int my, int[][] quad) {
        rt4.Player me = rt4.PlayerList.self;
        if (me == null) return null;
        int px = me.xFine >> 7, pz = me.zFine >> 7, n = 22;
        int x0 = Math.max(0, px - n), z0 = Math.max(0, pz - n), x1 = Math.min(103, px + n), z1 = Math.min(103, pz + n);
        int[][][] corner = new int[x1 - x0 + 2][z1 - z0 + 2][];
        for (int x = x0; x <= x1 + 1; x++) for (int z = z0; z <= z1 + 1; z++)
            corner[x - x0][z - z0] = API.CalculateSceneGraphScreenPosition(Math.min(x, 104) << 7, Math.min(z, 104) << 7, 0);
        int[] best = null;
        int bestD = Integer.MAX_VALUE;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            int[] a = corner[x - x0][z - z0], b = corner[x - x0 + 1][z - z0], c = corner[x - x0 + 1][z - z0 + 1], d = corner[x - x0][z - z0 + 1];
            if (a[0] < 0 || b[0] < 0 || c[0] < 0 || d[0] < 0) continue;
            if (!inQuad(mx, my, a, b, c, d)) continue;
            int dd = Math.abs(x - px) + Math.abs(z - pz);   // overlapping outlines (hills): the nearer tile
            if (dd < bestD) { bestD = dd; best = new int[]{x, z}; quad[0] = a; quad[1] = b; quad[2] = c; quad[3] = d; }
        }
        if (best == null) return null;
        return new int[]{rt4.Camera.originX + best[0], rt4.Camera.originZ + best[1], rt4.Player.plane};
    }

    private static boolean inQuad(int x, int y, int[]... p) {
        int sign = 0;
        for (int i = 0; i < p.length; i++) {
            int[] a = p[i], b = p[(i + 1) % p.length];
            long cross = (long) (b[0] - a[0]) * (y - a[1]) - (long) (b[1] - a[1]) * (x - a[0]);
            int s = cross > 0 ? 1 : cross < 0 ? -1 : 0;
            if (s == 0) continue;
            if (sign == 0) sign = s; else if (s != sign) return false;
        }
        return true;
    }

    /** The 3D view in the fixed layout (the interfaces around it are only redrawn when they change); null in resizable. */
    private static java.awt.Rectangle view() {
        return API.GetWindowMode() == WindowMode.FIXED ? new java.awt.Rectangle(4, 4, 512, 334) : null;
    }

    private static void line(int x0, int y0, int x1, int y1, int color) {
        java.awt.Rectangle v = view();
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
        for (int i = 0; i < 4000; i++) {
            if (v == null || v.contains(x0, y0)) API.FillRect(x0, y0, 1, 1, color, 0);
            if (x0 == x1 && y0 == y1) return;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    private void grabClick(MouseEvent e) {
        swallowRelease = true;
        if (e.getButton() != MouseEvent.BUTTON1) { stopGrab(null); return; }
        int[] t = tileAt(e.getX(), e.getY(), new int[4][]);
        java.awt.Rectangle v = view();
        if (t == null || (v != null && !v.contains(e.getX(), e.getY()))) { API.SendMessage("That's not a tile. Click the ground in the game view."); return; }
        String text = coords(t);
        try { java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text), null); } catch (Exception ignored) {}
        API.SendMessage("Copied " + text + ".");
        stopGrab(t);
    }

    /** Ends grabbing here and tells the Playground (with the tile picked, or null when cancelled). */
    private void stopGrab(int[] t) {
        grabbing = false;
        grabStopped = System.currentTimeMillis();
        new Thread(() -> send(t == null ? "grab off" : "grabbed " + t[0] + " " + t[1] + " " + t[2]), "codewindow-grab").start();
    }

    /** The picker pointer while grabbing, the normal one otherwise. */
    private static void updateCursor() {
        java.awt.Canvas c = GameShell.canvas;
        if (c == null) return;
        if (grabbing) {
            if (pickerCursor == null) pickerCursor = makePickerCursor();
            if (c.getCursor() != pickerCursor) c.setCursor(pickerCursor);
            cursorSet = true;
        } else if (cursorSet) {
            cursorSet = false;
            c.setCursor(java.awt.Cursor.getDefaultCursor());
        }
    }

    /** An eyedropper whose tip (bottom left) is the point that picks. */
    private static java.awt.Cursor makePickerCursor() {
        java.awt.Dimension size = java.awt.Toolkit.getDefaultToolkit().getBestCursorSize(32, 32);
        int w = Math.max(32, size.width), h = Math.max(32, size.height);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new java.awt.BasicStroke(6f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        g.setColor(java.awt.Color.BLACK);
        g.drawLine(2, 29, 19, 12);                                // glass tube outline
        g.setStroke(new java.awt.BasicStroke(3.4f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        g.setColor(java.awt.Color.WHITE);
        g.drawLine(3, 28, 19, 12);
        g.setColor(java.awt.Color.BLACK);
        g.fillOval(16, 3, 14, 14);                               // rubber bulb
        g.setColor(new java.awt.Color(0xe0b040));
        g.fillOval(18, 5, 10, 10);
        g.setColor(java.awt.Color.BLACK);
        g.setStroke(new java.awt.BasicStroke(2.6f));
        g.drawLine(14, 10, 21, 17);                              // collar
        g.dispose();
        return java.awt.Toolkit.getDefaultToolkit().createCustomCursor(img, new java.awt.Point(1, 30), "picker");
    }

    /*
     * Settings that stay saved. The game keeps its settings in a small preferences file whose location depends on the
     * computer, and resets the display to Standard detail whenever a start did not reach the title screen. So Code
     * Edition keeps its own copy in the plugin storage (game/plugins/plsto) and puts it back at every start.
     * HD is the default until the player picks Standard detail themselves.
     */
    private static final String KEY_PREFS = "codewindow-prefs", KEY_HD = "codewindow-hd";

    /** Runs before the game loads (plugin Init), so the game starts up with these settings. Once per start. */
    private static void restoreSettings() {
        if (System.getProperty("codewindow.restored") != null) return;   // plugins are re-created on every display change
        System.setProperty("codewindow.restored", "1");
        try {
            boolean safeStart = rt4.Preferences.safeMode;   // last start did not reach the title screen
            Object saved = API.GetData(KEY_PREFS);
            if (saved instanceof byte[]) rt4.Preferences.decode(new rt4.Buffer((byte[]) saved));
            rt4.Preferences.safeMode = safeStart;
            Object hd = API.GetData(KEY_HD);
            boolean wantHD = hd instanceof Boolean ? (Boolean) hd : !Boolean.getBoolean("codewindow.nohd");
            if (!wantHD) rt4.Preferences.favoriteWorlds = 0;
            else if (rt4.Preferences.favoriteWorlds == 0) rt4.Preferences.favoriteWorlds = 1;
            rt4.Preferences.write(GameShell.signLink);
            System.out.println("[CodeWindow] settings restored" + (saved instanceof byte[] ? "" : " (first start)")
                + ", " + (wantHD ? "HD" : "SD") + (safeStart ? ", but this start uses safe mode" : ""));
        } catch (Throwable t) {
            System.out.println("[CodeWindow] could not restore settings: " + t);
        }
    }

    private int settingsFrames;

    /** Watches for changes on the title screen and in game, and saves them. */
    private void rememberSettings() {
        int state = rt4.client.gameState;
        if ((state != 10 && state != 30) || rt4.InterfaceList.topLevelInterface == -1) return;
        if (++settingsFrames % 30 != 0) return;
        boolean changed = false;
        // HD or SD: only a change made after start-up counts as the player's choice (not a safe-mode start)
        String hdNow = String.valueOf(API.IsHD()), hdSeen = System.getProperty("codewindow.seenHD");
        if (hdSeen == null) System.setProperty("codewindow.seenHD", hdNow);
        else if (!hdSeen.equals(hdNow)) {
            System.setProperty("codewindow.seenHD", hdNow);
            API.StoreData(KEY_HD, API.IsHD());
            System.out.println("[CodeWindow] remembering " + (API.IsHD() ? "HD" : "SD"));
            changed = true;
        }
        rt4.Buffer b = rt4.Preferences.encode();
        byte[] now = java.util.Arrays.copyOf(b.data, b.offset);
        Object saved = API.GetData(KEY_PREFS);
        if (!(saved instanceof byte[]) || !java.util.Arrays.equals((byte[]) saved, now)) {
            API.StoreData(KEY_PREFS, now);
            changed = true;
        }
        if (changed) PluginRepository.SaveStorage();
    }

    /** Switch to resizable once per login, unless turned off with ::autoresize. */
    private void autoResize() {
        if (resizeDone) return;
        if (rt4.client.gameState != 30) return;
        if (rt4.InterfaceList.topLevelInterface != 548) { framesInGame = 0; return; }  // still on the welcome screen ("Click here to play")
        if (++framesInGame < 25) return;                                   // let the game screen settle first
        resizeDone = true;
        if (Boolean.TRUE.equals(API.GetData("codewindow-no-autoresize")) || Boolean.getBoolean("codewindow.noautoresize")) return;
        if (API.GetWindowMode() != WindowMode.FIXED) return;
        try {
            if (API.IsHD()) DisplayMode.setWindowMode(true, 2, -1, -1);
            else { DisplayMode.resizableSD = true; DisplayMode.setWindowMode(true, 0, false, 0, GameShell.frameWidth, GameShell.frameHeight); } // SD resizable, no OpenGL needed
        } catch (Throwable t) { System.out.println("[CodeWindow] could not switch to resizable: " + t); }
    }

    private boolean inside(int x, int y) {
        int dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy <= R * R;
    }

    /** Asks the launcher (through the game backend) to open the Playground, or bring it to the front if it's open. */
    private void openWindow() {
        new Thread(() -> {
            if (!"ok".equals(send("orb"))) API.SendMessage("Start the Script Playground from the Code Edition launcher.");
        }, "codewindow-orb").start();
    }

    /** Tells the backend once a second that the game is running and where its window is. */
    private void heartbeat() {
        int beat = 0;
        while (true) {
            try {
                String g = send("grabbing");
                if (g != null && System.currentTimeMillis() - grabStopped > 1000) grabbing = g.equals("on");
                if (beat++ % 4 != 0) { Thread.sleep(250); continue; }
                java.awt.Window w = GameShell.canvas == null ? null : javax.swing.SwingUtilities.getWindowAncestor(GameShell.canvas);
                java.awt.Rectangle b = w != null && w.isShowing() ? w.getBounds() : new java.awt.Rectangle();
                String r = send("client " + b.x + " " + b.y + " " + b.width + " " + b.height);
                if (r != null && r.startsWith("move ") && w != null) {   // the Playground made room beside the game
                    String[] c = r.split(" ");
                    java.awt.Rectangle to = new java.awt.Rectangle(Integer.parseInt(c[1]), Integer.parseInt(c[2]), Integer.parseInt(c[3]), Integer.parseInt(c[4]));
                    java.awt.Window win = w;
                    java.awt.EventQueue.invokeLater(() -> {
                        if (win instanceof java.awt.Frame) ((java.awt.Frame) win).setExtendedState(java.awt.Frame.NORMAL);
                        win.setBounds(to);
                    });
                }
                Thread.sleep(250);
            } catch (InterruptedException e) { return; }
            catch (Throwable ignored) {}
        }
    }

    private synchronized String send(String line) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (link == null) {
                    link = new Socket();
                    link.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
                    if (link.getLocalPort() == PORT) { link.close(); link = null; throw new IOException("no game yet"); }
                    link.setSoTimeout(3000);
                    linkIn = new BufferedReader(new InputStreamReader(link.getInputStream(), StandardCharsets.UTF_8));
                    linkOut = new PrintWriter(new OutputStreamWriter(link.getOutputStream(), StandardCharsets.UTF_8), true);
                }
                linkOut.println(line);
                String r = linkIn.readLine();
                if (r != null) return r;
            } catch (IOException ignored) {}
            try { if (link != null) link.close(); } catch (IOException ignored) {}
            link = null;
        }
        return null;
    }

    // ---- drawing: filled circles made of one-pixel-high rectangles, so it works in both SD and HD ----
    private static void disc(int cx, int cy, double r, int color) {
        for (int y = (int) -Math.floor(r); y <= Math.floor(r); y++) {
            int half = (int) Math.floor(Math.sqrt(r * r - y * y) + 0.5);
            if (half > 0) API.FillRect(cx - half, cy + y, half * 2, 1, color, 0);
        }
    }

    static void drawOrb(int cx, int cy, boolean hover) {
        disc(cx, cy, R, 0x1e160c);                         // dark outline
        disc(cx, cy, R - 1, 0x96703e);                     // bronze rim
        disc(cx, cy, R - 2, hover ? 0xf0cc80 : 0xcea86a);  // rim highlight
        disc(cx, cy, R - 3, 0x785830);                     // inner bevel
        disc(cx, cy, R - 4, 0x281c0e);
        disc(cx, cy, R - 4.6, 0x000000);                   // black face
        double ro = 8.5, ri = 4.8;                         // white "C": a thick ring open on the right
        for (int y = -9; y <= 9; y++) for (int x = -9; x <= 9; x++) {
            double dist = Math.sqrt(x * x + y * y);
            if (dist > ro || dist < ri) continue;
            double ang = Math.toDegrees(Math.atan2(-y, x));
            if (ang > -42 && ang < 42) continue;
            API.FillRect(cx + x, cy + y, 1, 1, 0xffffff, 0);
        }
    }
}
