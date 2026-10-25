package codeedition;

import codeedition.BlockModel.Block;
import codeedition.BlockModel.Def;
import codeedition.BlockModel.Shape;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The block canvas: a dark node-graph style grid where blocks snap together top to bottom, Scratch style.
 * Loops and Ifs wrap around the blocks inside them. Drag blocks to move them, drag them off to delete them,
 * click the orange slot to pick a target. The running block glows; a block that stopped the script turns red.
 * Ctrl+click, Shift+click or a box dragged on empty canvas picks several blocks; copy, cut and paste work between
 * scripts (the clipboard holds the blocks, and their JavaScript for pasting anywhere else).
 */
final class BlockCanvas extends JComponent implements Scrollable {
    // look
    static final Color BG = new Color(0x1a1b1e), GRID = new Color(0x222327), GRID_BIG = new Color(0x2a2b30);
    static final Color SLOT_BG = new Color(0x15161a), SLOT_TEXT = new Color(0xffb35c), HAT = new Color(0xb3403a);
    static final Color RUN_GLOW = new Color(0xffd54a), FAIL = new Color(0xff5252), SELECT = new Color(0xffffff);
    static final int ROW = 34, ARM = 16, FOOT = 18, EMPTY = 24, ELSE_ROW = 26, GAP = 4, X0 = 28, Y0 = 26;
    static final Font LABEL = new Font(Font.SANS_SERIF, Font.BOLD, 13), SLOT = new Font(Font.SANS_SERIF, Font.BOLD, 12);

    /** For measuring text outside of painting. */
    static final Graphics2D MEASURE = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics();
    static {
        MEASURE.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        MEASURE.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    }

    private final List<Block> script = new ArrayList<>();
    private final List<Placed> placed = new ArrayList<>();
    private final List<Insert> inserts = new ArrayList<>();
    private Block selected, running, failed;
    private final Set<Block> picked = Collections.newSetFromMap(new IdentityHashMap<>());   // all selected blocks
    private Point marqueeFrom, marqueeTo;     // a box being dragged on empty canvas to pick blocks
    private Supplier<JPopupMenu> menu = JPopupMenu::new;   // the right-click menu (the Playground's Tools items)
    private String failReason = "";
    private Runnable onChange = () -> {};
    private Consumer<Block> onSelect = b -> {};
    private final List<String> undo = new ArrayList<>();

    // dragging
    private Block dragging;               // block (with the blocks inside it) being dragged
    private boolean dragFromCanvas;
    private Point dragAt, pressAt;
    private Block pressBlock;
    private List<Block> dragOldList; private int dragOldIndex;
    private Insert hover;
    private String dropCondition;         // a condition chip dragged from the palette
    private Block conditionHover;

    /** One block as laid out on screen. */
    static final class Placed {
        final Block b; final Rectangle head; final Rectangle whole; Rectangle slot; final List<Block> list; final int index;
        Placed(Block b, Rectangle head, Rectangle whole, List<Block> list, int index) { this.b = b; this.head = head; this.whole = whole; this.list = list; this.index = index; }
    }
    /** A place where a dragged block can go. */
    static final class Insert {
        final List<Block> list; final int index; final int x, y, w;
        Insert(List<Block> list, int index, int x, int y, int w) { this.list = list; this.index = index; this.x = x; this.y = y; this.w = w; }
    }

    BlockCanvas() {
        setOpaque(true);
        setFocusable(true);
        MouseAdapter m = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { pressed(e); }
            @Override public void mouseDragged(MouseEvent e) { dragged(e.getPoint()); }
            @Override public void mouseReleased(MouseEvent e) { released(e.getPoint()); }
            @Override public void mouseMoved(MouseEvent e) { setToolTipText(tipAt(e.getPoint())); }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        ToolTipManager.sharedInstance().registerComponent(this);
        bindKey(KeyEvent.VK_DELETE, 0, "delete", this::deleteSelected);
        bindKey(KeyEvent.VK_BACK_SPACE, 0, "delete2", this::deleteSelected);
        bindKey(KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx(), "undo", this::undo);
        int ctrl = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        bindKey(KeyEvent.VK_D, ctrl, "duplicate", this::duplicateSelected);
        bindKey(KeyEvent.VK_C, ctrl, "copy", this::copySelection);
        bindKey(KeyEvent.VK_X, ctrl, "cut", this::cutSelection);
        bindKey(KeyEvent.VK_V, ctrl, "paste", this::paste);
        bindKey(KeyEvent.VK_A, ctrl, "selectAll", this::selectAll);
        bindKey(KeyEvent.VK_ESCAPE, 0, "cancel", this::cancelDrag);
    }

    private void bindKey(int key, int mods, String name, Runnable r) {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, mods), name);
        getActionMap().put(name, new AbstractAction() { public void actionPerformed(ActionEvent e) { r.run(); } });
    }

    // ------------------------------------------------------------------ script in and out
    void setScript(List<Block> blocks) {
        script.clear(); script.addAll(blocks);
        selected = null; running = null; failed = null; failReason = "";
        picked.clear();
        undo.clear();
        relayout();
        onSelect.accept(null);
    }
    List<Block> script() { return script; }
    void onChange(Runnable r) { onChange = r; }
    void onSelect(Consumer<Block> c) { onSelect = c; }
    Block selected() { return selected; }
    boolean hasSelection() { return !picked.isEmpty(); }
    /** The right-click menu: the Playground fills it with the same items as its Tools button. */
    void menu(Supplier<JPopupMenu> m) { menu = m; }

    /** Call before every change so Ctrl+Z can put it back. */
    void remember() {
        undo.add(BlockModel.toJson(script));
        if (undo.size() > 60) undo.remove(0);
    }
    private void changed() { failed = null; failReason = ""; relayout(); onChange.run(); }

    void undo() {
        if (undo.isEmpty()) return;
        List<Block> prev = BlockModel.fromJson(undo.remove(undo.size() - 1));
        script.clear(); script.addAll(prev);
        selected = null;
        picked.clear();
        changed();
        onSelect.accept(null);
    }

    /** Sets one slot of a block (slot 0 is the first). */
    void setArg(Block b, int i, String v) {
        if (b == null || v == null || v.equals(b.arg(i))) return;
        remember();
        b.set(i, v);
        changed();
    }

    void setTarget(Block b, String target, String value) {
        if (b == null) return;
        remember();
        if (target != null) b.set(0, target);
        if (value != null) b.set(1, value);
        changed();
    }

    void addAtEnd(Block b) {
        remember();
        List<Block> into = script;
        if (selected != null && selected.def().shape != Shape.PLAIN) into = selected.body;
        into.add(b);
        select(b);
        changed();
    }

    void showRunning(Block b) { if (running != b || failed != null) { running = b; failed = null; failReason = ""; repaint(); } }
    void showFailed(Block b, String reason) { failed = b; running = null; failReason = reason; repaint(); }
    void clearRun() { if (running != null || failed != null) { running = null; failed = null; failReason = ""; repaint(); } }

    private void select(Block b) {
        selected = b;
        picked.clear();
        if (b != null) picked.add(b);
        repaint();
        onSelect.accept(b);
    }

    /** Picks several blocks; the first one shows in the Block panel. */
    private void selectMany(List<Block> l) {
        picked.clear();
        picked.addAll(l);
        selected = l.isEmpty() ? null : l.get(0);
        repaint();
        onSelect.accept(selected);
    }

    void selectAll() { selectMany(new ArrayList<>(script)); requestFocusInWindow(); }

    /** The picked blocks in script order, leaving out any inside another picked block (they come with it). */
    private List<Block> pickedInOrder() {
        List<Placed> ps = new ArrayList<>();
        for (Placed p : placed) if (picked.contains(p.b)) ps.add(p);
        ps.sort((a, b) -> a.head.y != b.head.y ? Integer.compare(a.head.y, b.head.y) : Integer.compare(a.head.x, b.head.x));
        List<Block> out = new ArrayList<>();
        for (Placed p : ps) {
            boolean inside = false;
            for (Block o : picked) if (o != p.b && (contains(o.body, p.b) || contains(o.elseBody, p.b))) inside = true;
            if (!inside) out.add(p.b);
        }
        return out;
    }

    private static boolean contains(List<Block> l, Block b) {
        for (Block x : l) if (x == b || contains(x.body, b) || contains(x.elseBody, b)) return true;
        return false;
    }

    /** Where new blocks go: after the last picked block in its list, else at the end of the script. */
    private Insert insertPoint() {
        List<Block> l = pickedInOrder();
        if (!l.isEmpty()) {
            Block last = l.get(l.size() - 1);
            for (Placed p : placed) if (p.b == last) return new Insert(p.list, p.index + 1, 0, 0, 0);
        }
        return new Insert(script, script.size(), 0, 0, 0);
    }

    void deleteSelected() {
        List<Block> l = pickedInOrder();
        if (l.isEmpty()) return;
        remember();
        for (Block b : l) removeFrom(script, b);
        selected = null;
        picked.clear();
        changed();
        onSelect.accept(null);
    }

    void duplicateSelected() {
        List<Block> l = pickedInOrder();
        if (l.isEmpty()) return;
        List<Block> copies = new ArrayList<>();
        for (Block b : l) copies.add(b.copy());
        insertBlocks(copies);
    }

    /** Puts blocks after the picked ones (or at the end), and picks them. */
    void insertBlocks(List<Block> blocks) {
        if (blocks.isEmpty()) return;
        remember();
        Insert at = insertPoint();
        at.list.addAll(Math.min(at.index, at.list.size()), blocks);
        changed();
        selectMany(blocks);
        requestFocusInWindow();
    }

    // ------------------------------------------------------------------ clipboard
    /** Blocks on the clipboard: our own flavour inside the Playground, plain text (their JavaScript) everywhere else. */
    static final DataFlavor BLOCKS = new DataFlavor("application/x-code-edition-blocks;class=java.lang.String", "Code Edition blocks");

    boolean copySelection() {
        List<Block> l = pickedInOrder();
        if (l.isEmpty()) return false;
        List<Block> copies = new ArrayList<>();
        for (Block b : l) copies.add(b.copy());
        String json = BlockModel.toJson(copies), js = innerCode(copies);
        Transferable t = new Transferable() {
            public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{BLOCKS, DataFlavor.stringFlavor}; }
            public boolean isDataFlavorSupported(DataFlavor f) { return f.equals(BLOCKS) || f.equals(DataFlavor.stringFlavor); }
            public Object getTransferData(DataFlavor f) { return f.equals(BLOCKS) ? json : js; }
        };
        try { clipboard().setContents(t, null); } catch (Exception e) { return false; }
        return true;
    }

    void cutSelection() { if (copySelection()) deleteSelected(); }

    void paste() {
        // map coordinates (from the coordinate grabber): into the selected Walk To or Is At, or as a new Walk To
        String xy = clipboardCoords();
        if (xy != null) {
            Block b = picked.size() <= 1 ? selected : null;
            if (b != null && b.type.equals("walk")) { setArg(b, 0, xy); return; }
            if (b != null && b.def().kind == BlockModel.Kind.CONDITION && b.target().equals("Is At")) { setTarget(b, null, xy); return; }
            Block w = new Block("walk");
            w.set(0, xy);
            insertBlocks(new ArrayList<>(List.of(w)));
            return;
        }
        insertBlocks(clipboardBlocks());
    }

    /** The clipboard as "3222, 3218" when it holds plain map coordinates, otherwise null. */
    static String clipboardCoords() {
        try {
            Clipboard c = clipboard();
            if (c.isDataFlavorAvailable(BLOCKS) || !c.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null;
            String t = ((String) c.getData(DataFlavor.stringFlavor)).trim();
            if (!t.matches("\\(?\\s*\\d{3,5}\\s*[,\\s]\\s*\\d{3,5}(\\s*[,\\s]\\s*\\d)?\\s*\\)?")) return null;
            int[] v = TargetPicker.parseCoords(t);
            return TargetPicker.coordText(v[0], v[1], v[2]);
        } catch (Exception e) { return null; }
    }

    /** True when the clipboard holds blocks or code that can be pasted as blocks. */
    boolean canPaste() {
        try { Clipboard c = clipboard(); return c.isDataFlavorAvailable(BLOCKS) || c.isDataFlavorAvailable(DataFlavor.stringFlavor); }
        catch (Exception e) { return false; }
    }

    /** The clipboard as blocks: copied blocks, a block script file's text, or JavaScript (code that isn't a block becomes a JavaScript block). */
    static List<Block> clipboardBlocks() {
        try {
            Clipboard c = clipboard();
            if (c.isDataFlavorAvailable(BLOCKS)) return BlockModel.fromJson((String) c.getData(BLOCKS));
            if (!c.isDataFlavorAvailable(DataFlavor.stringFlavor)) return new ArrayList<>();
            return textToBlocks((String) c.getData(DataFlavor.stringFlavor));
        } catch (Exception e) { return new ArrayList<>(); }
    }

    /** Text from a file or the clipboard as blocks to insert into a script. */
    static List<Block> textToBlocks(String text) {
        if (text == null || text.trim().isEmpty()) return new ArrayList<>();
        if (text.contains("\"code-edition-blocks\"")) {
            try { return BlockModel.fromJson(text); } catch (RuntimeException ignored) {}
        }
        List<Block> l = JsToBlocks.parse(text);
        if (l.size() == 1 && l.get(0).type.equals("jsfile")) {
            // no blocks wrapper: statements copied from a script body, or a whole JavaScript file
            l = JsToBlocks.parse("blocks.run(async () => {\n" + text + "\n});");
        }
        List<Block> out = new ArrayList<>();
        for (Block b : l) out.add(b.type.equals("jsfile") ? JsToBlocks.code(b.arg(0)) : b);
        return out;
    }

    /** The JavaScript of these blocks without the blocks.run wrapper, ready to paste into a script body. */
    static String innerCode(List<Block> blocks) {
        String code = BlockModel.compile(blocks);
        int open = code.indexOf("blocks.run(async () => {\n"), close = code.lastIndexOf("});");
        if (open < 0 || close < open) return code;
        StringBuilder sb = new StringBuilder();
        for (String line : code.substring(open + "blocks.run(async () => {\n".length(), close).split("\n", -1))
            sb.append(line.startsWith("  ") ? line.substring(2) : line).append('\n');
        return sb.toString().replaceAll("\\s+$", "") + "\n";
    }

    private static Clipboard clipboard() { return Toolkit.getDefaultToolkit().getSystemClipboard(); }

    private static boolean removeFrom(List<Block> l, Block b) {
        if (l.remove(b)) return true;
        for (Block x : l) if (removeFrom(x.body, b) || removeFrom(x.elseBody, b)) return true;
        return false;
    }

    // ------------------------------------------------------------------ layout
    private int contentW, contentH;

    void relayout() {
        placed.clear(); inserts.clear();
        Graphics2D g = MEASURE;
        int y = Y0 + ROW + GAP;     // under the "When Run is pressed" block
        contentW = 360;
        y = layoutList(g, script, X0, y);
        contentH = y + 120;
        revalidate();
        repaint();
    }

    private int layoutList(Graphics2D g, List<Block> list, int x, int y) {
        int w = Math.max(240, listWidth(g, list));
        inserts.add(new Insert(list, 0, x, y - GAP / 2, w));
        for (int i = 0; i < list.size(); i++) {
            Block b = list.get(i);
            int hw = headWidth(g, b);
            Rectangle head = new Rectangle(x, y, hw, ROW);
            int top = y;
            y += ROW;
            Shape shape = b.def() == null ? Shape.PLAIN : b.def().shape;
            if (shape != Shape.PLAIN) {
                y += GAP;
                int end = layoutList(g, b.body, x + ARM, y);
                y = Math.max(end, y + EMPTY);
                if (shape == Shape.IF_ELSE) {
                    y += ELSE_ROW - GAP;
                    int e2 = layoutList(g, b.elseBody, x + ARM, y + GAP);
                    y = Math.max(e2, y + GAP + EMPTY);
                }
                y += FOOT - GAP;
            }
            Rectangle whole = new Rectangle(x, top, Math.max(hw, shape == Shape.PLAIN ? hw : blockWidth(g, b)), y - top);
            Placed p = new Placed(b, head, whole, list, i);
            p.slot = slotRect(g, b, head);
            placed.add(p);
            contentW = Math.max(contentW, whole.x + whole.width + 40);
            y += GAP;
            inserts.add(new Insert(list, i + 1, x, y - GAP / 2, w));
        }
        return y;
    }

    private int listWidth(Graphics2D g, List<Block> l) {
        int w = 0;
        for (Block b : l) w = Math.max(w, blockWidth(g, b));
        return w;
    }

    static int blockWidth(Graphics2D g, Block b) {
        int w = headWidth(g, b);
        if (b.def() != null && b.def().shape != Shape.PLAIN) {
            w = Math.max(w, ARM + Math.max(200, maxWidth(g, b.body)));
            w = Math.max(w, ARM + Math.max(200, maxWidth(g, b.elseBody)));
        }
        return w;
    }
    static int compactWidth(Graphics2D g, Block b) {
        return Math.max(g.getFontMetrics(LABEL).stringWidth(b.def().label) + 30, 150);
    }
    private static int maxWidth(Graphics2D g, List<Block> l) { int w = 0; for (Block b : l) w = Math.max(w, blockWidth(g, b)); return w; }

    static int headWidth(Graphics2D g, Block b) {
        Def d = b.def();
        int w = 16 + g.getFontMetrics(LABEL).stringWidth(d == null ? b.type : d.label) + 14;
        String s = b.slotText();
        if (!s.isEmpty()) w += g.getFontMetrics(SLOT).stringWidth(s) + 26 + 14;
        return Math.max(w, 150);
    }

    private static Rectangle slotRect(Graphics2D g, Block b, Rectangle head) {
        String s = b.slotText();
        if (s.isEmpty()) return null;
        Def d = b.def();
        int lx = head.x + 16 + g.getFontMetrics(LABEL).stringWidth(d.label) + 22;
        return new Rectangle(lx, head.y + 6, g.getFontMetrics(SLOT).stringWidth(s) + 26, ROW - 12);
    }

    @Override public Dimension getPreferredSize() { return new Dimension(contentW, Math.max(contentH, 300)); }
    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
    @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - 40; }
    @Override public boolean getScrollableTracksViewportWidth() { return getParent() instanceof JViewport && getParent().getWidth() > getPreferredSize().width; }
    @Override public boolean getScrollableTracksViewportHeight() { return getParent() instanceof JViewport && getParent().getHeight() > getPreferredSize().height; }

    // ------------------------------------------------------------------ painting
    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, getWidth(), getHeight());
        for (int x = 0; x < getWidth(); x += 16) { g.setColor(x % 128 == 0 ? GRID_BIG : GRID); g.drawLine(x, 0, x, getHeight()); }
        for (int y = 0; y < getHeight(); y += 16) { g.setColor(y % 128 == 0 ? GRID_BIG : GRID); g.drawLine(0, y, getWidth(), y); }

        paintHat(g, X0, Y0);
        // paint outer blocks first so inner ones sit on top
        for (int i = placed.size() - 1; i >= 0; i--) paintPlaced(g, placed.get(i));

        if (script.isEmpty() && dragging == null) {
            g.setFont(LABEL.deriveFont(Font.PLAIN, 13f));
            g.setColor(new Color(0x7b7f87));
            g.drawString("Drag blocks here from the left, under “When Run is pressed”.", X0, Y0 + ROW + 34);
            g.drawString("Click a block's orange slot to choose a target on the right.", X0, Y0 + ROW + 56);
        }
        if (failed != null && !failReason.isEmpty()) paintFailBanner(g);
        if (marqueeFrom != null && marqueeTo != null) {
            Rectangle r = marqueeRect();
            g.setColor(new Color(0x9ecbff & 0xffffff | 0x30000000, true));
            g.fill(r);
            g.setColor(new Color(0x9ecbff));
            g.draw(r);
        }
        if (dragging != null && dragAt != null) {
            if (hover != null) {
                g.setColor(SELECT);
                g.fillRoundRect(hover.x, hover.y - 2, Math.max(120, hover.w), 4, 4, 4);
            }
            Composite c = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.8f));
            paintFloating(g, dragging, dragAt.x - 20, dragAt.y - ROW / 2);
            g.setComposite(c);
        }
        g.dispose();
    }

    private void paintHat(Graphics2D g, int x, int y) {
        int w = 210;
        RoundRectangle2D r = new RoundRectangle2D.Float(x, y, w, ROW, 14, 14);
        g.setColor(HAT);
        g.fill(r);
        g.setColor(HAT.brighter());
        g.draw(r);
        g.setColor(Color.WHITE);
        g.setFont(LABEL);
        g.drawString("▶  When Run is pressed", x + 14, y + 22);
    }

    private void paintPlaced(Graphics2D g, Placed p) {
        Block b = p.b;
        Def d = b.def();
        Color c = d == null ? Color.GRAY : d.cat.color;
        Shape shape = d == null ? Shape.PLAIN : d.shape;
        if (shape == Shape.PLAIN) {
            paintBody(g, new RoundRectangle2D.Float(p.head.x, p.head.y, p.head.width, p.head.height, 12, 12), c);
        } else {
            // a C shape: header, arm down the left, footer
            int x = p.whole.x, y = p.whole.y, w = Math.max(p.whole.width, 200), h = p.whole.height;
            java.awt.geom.Area a = new java.awt.geom.Area(new RoundRectangle2D.Float(x, y, w, h, 12, 12));
            for (Rectangle hole : holes(p)) a.subtract(new java.awt.geom.Area(new RoundRectangle2D.Float(hole.x, hole.y, hole.width, hole.height, 8, 8)));
            paintBody(g, a, c);
            if (shape == Shape.IF_ELSE) {
                Rectangle h2 = holes(p).get(1);
                g.setColor(Color.WHITE);
                g.setFont(LABEL);
                g.drawString("Else", x + 14, h2.y - 8);
            }
        }
        g.setColor(Color.WHITE);
        g.setFont(LABEL);
        g.drawString(d == null ? b.type : d.label, p.head.x + 14, p.head.y + 22);
        if (p.slot != null) {
            boolean hot = conditionHover == b;
            g.setColor(hot ? new Color(0x3a2a14) : SLOT_BG);
            g.fillRoundRect(p.slot.x, p.slot.y, p.slot.width, p.slot.height, 10, 10);
            g.setColor(hot ? SLOT_TEXT : new Color(0x4a3a24));
            g.drawRoundRect(p.slot.x, p.slot.y, p.slot.width, p.slot.height, 10, 10);
            g.setColor(SLOT_TEXT);
            g.setFont(SLOT);
            g.drawString(b.slotText(), p.slot.x + 13, p.slot.y + 15);
            g.setColor(new Color(0xffffff, true));
            g.setColor(new Color(255, 255, 255, 140));
            g.drawString("→", p.slot.x - 17, p.slot.y + 15);
        }
        Shape2 outline = () -> shape == Shape.PLAIN ? new RoundRectangle2D.Float(p.head.x, p.head.y, p.head.width, p.head.height, 12, 12)
            : new RoundRectangle2D.Float(p.whole.x, p.whole.y, Math.max(p.whole.width, 200), p.whole.height, 12, 12);
        if (b == failed) glow(g, outline.get(), FAIL);
        else if (b == running) glow(g, (shape == Shape.PLAIN ? outline.get() : new RoundRectangle2D.Float(p.head.x, p.head.y, p.head.width, p.head.height, 12, 12)), RUN_GLOW);
        if (picked.contains(b)) {
            g.setColor(b == selected || picked.size() == 1 ? SELECT : new Color(0x9ecbff));
            g.setStroke(new BasicStroke(2f));
            g.draw(outline.get());
            g.setStroke(new BasicStroke(1f));
        }
    }
    private interface Shape2 { java.awt.Shape get(); }

    private List<Rectangle> holes(Placed p) {
        List<Rectangle> out = new ArrayList<>();
        int x = p.whole.x + ARM, w = Math.max(p.whole.width, 200) - ARM;
        int bodyTop = p.head.y + ROW;
        Block b = p.b;
        if (b.def().shape == Shape.IF_ELSE) {
            int bodyEnd = bodyBottom(b.body, bodyTop + GAP);
            out.add(new Rectangle(x, bodyTop, w, bodyEnd - bodyTop));
            int elseTop = bodyEnd + ELSE_ROW - GAP;
            out.add(new Rectangle(x, elseTop, w, p.whole.y + p.whole.height - FOOT - elseTop));
        } else {
            out.add(new Rectangle(x, bodyTop, w, p.whole.y + p.whole.height - FOOT - bodyTop));
        }
        return out;
    }

    /** Bottom of the area a list of blocks takes (at least the empty drop area). */
    private int bodyBottom(List<Block> l, int top) {
        int bottom = top + EMPTY;
        for (Placed q : placed) if (q.list == l) bottom = Math.max(bottom, q.whole.y + q.whole.height + GAP);
        return bottom;
    }

    private static void paintBody(Graphics2D g, java.awt.Shape s, Color c) {
        Rectangle r = s.getBounds();
        g.setPaint(new GradientPaint(0, r.y, c.brighter(), 0, r.y + ROW, c));
        g.fill(s);
        g.setPaint(null);
        g.setColor(c.darker());
        g.draw(s);
    }

    private static void glow(Graphics2D g, java.awt.Shape s, Color c) {
        Stroke old = g.getStroke();
        for (int i = 4; i >= 1; i--) {
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 40 + (4 - i) * 30));
            g.setStroke(new BasicStroke(i * 2f));
            g.draw(s);
        }
        g.setColor(c);
        g.setStroke(new BasicStroke(2f));
        g.draw(s);
        g.setStroke(old);
    }

    private void paintFailBanner(Graphics2D g) {
        Rectangle vis = getVisibleRect();
        g.setFont(SLOT);
        String text = "Stopped: " + failReason;
        FontMetrics fm = g.getFontMetrics();
        int maxW = Math.max(200, vis.width - 40);
        List<String> lines = wrap(text, fm, maxW - 24);
        int h = 12 + lines.size() * 17;
        int x = vis.x + 20, y = vis.y + vis.height - h - 14;
        g.setColor(new Color(0x3a1517));
        g.fillRoundRect(x, y, maxW, h, 10, 10);
        g.setColor(FAIL);
        g.drawRoundRect(x, y, maxW, h, 10, 10);
        g.setColor(new Color(0xffd0d0));
        for (int i = 0; i < lines.size(); i++) g.drawString(lines.get(i), x + 12, y + 20 + i * 17);
    }

    static List<String> wrap(String text, FontMetrics fm, int w) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && fm.stringWidth(line + " " + word) > w) { out.add(line.toString()); line.setLength(0); }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    /** Draws a block (and what is inside it) away from the script, for dragging and for the palette. */
    static void paintFloating(Graphics2D g, Block b, int x, int y) { paintFloating(g, b, x, y, false); }

    /** compact: the palette's version, without the target slot. */
    static void paintFloating(Graphics2D g, Block b, int x, int y, boolean compact) {
        Def d = b.def();
        int w = compact ? compactWidth(g, b) : headWidth(g, b);
        Color c = d.cat.color;
        if (d.shape == Shape.PLAIN) paintBody(g, new RoundRectangle2D.Float(x, y, w, ROW, 12, 12), c);
        else {
            int innerH = Math.max(EMPTY, b.body.size() * (ROW + GAP));
            int h = ROW + GAP + innerH + FOOT + (d.shape == Shape.IF_ELSE ? ELSE_ROW + EMPTY : 0);
            int ww = compact ? w : Math.max(w, 200);
            java.awt.geom.Area a = new java.awt.geom.Area(new RoundRectangle2D.Float(x, y, ww, h, 12, 12));
            a.subtract(new java.awt.geom.Area(new RoundRectangle2D.Float(x + ARM, y + ROW, ww - ARM, innerH + GAP, 8, 8)));
            if (d.shape == Shape.IF_ELSE)
                a.subtract(new java.awt.geom.Area(new RoundRectangle2D.Float(x + ARM, y + ROW + GAP + innerH + ELSE_ROW, ww - ARM, EMPTY - GAP, 8, 8)));
            paintBody(g, a, c);
            int yy = y + ROW + GAP;
            for (Block in : b.body) { paintFloating(g, in, x + ARM, yy); yy += ROW + GAP; if (yy > y + ROW + innerH) break; }
            if (d.shape == Shape.IF_ELSE) { g.setColor(Color.WHITE); g.setFont(LABEL); g.drawString("Else", x + 14, y + ROW + GAP + innerH + 18); }
        }
        g.setColor(Color.WHITE);
        g.setFont(LABEL);
        g.drawString(d.label, x + 14, y + 22);
        String s = compact ? "" : b.slotText();
        if (!s.isEmpty()) {
            int sx = x + 16 + g.getFontMetrics(LABEL).stringWidth(d.label) + 22;
            int sw = g.getFontMetrics(SLOT).stringWidth(s) + 26;
            g.setColor(SLOT_BG);
            g.fillRoundRect(sx, y + 6, sw, ROW - 12, 10, 10);
            g.setColor(SLOT_TEXT);
            g.setFont(SLOT);
            g.drawString(s, sx + 13, y + 21);
            g.setColor(new Color(255, 255, 255, 140));
            g.drawString("→", sx - 17, y + 21);
        }
    }

    // ------------------------------------------------------------------ mouse
    private Placed at(Point pt) {
        for (Placed p : placed) if (p.head.contains(pt)) return p;   // inner blocks are listed before outer ones
        for (Placed p : placed) if (p.whole.contains(pt) && pt.x < p.whole.x + ARM) return p;   // the left arm of a loop
        for (Placed p : placed) if (p.whole.contains(pt) && pt.y > p.whole.y + p.whole.height - FOOT) return p;   // its foot
        return null;
    }

    private void pressed(MouseEvent e) {
        requestFocusInWindow();
        Placed p = at(e.getPoint());
        if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger()) {
            if (p != null && !picked.contains(p.b)) select(p.b);   // right-click inside a multi-pick keeps it
            popup(p, e.getPoint());
            return;
        }
        pressAt = e.getPoint();
        boolean add = (e.getModifiersEx() & Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()) != 0, range = e.isShiftDown();
        if (p == null) {
            pressBlock = null;
            if (!add && !range) select(null);
            marqueeFrom = e.getPoint();     // drag a box to pick blocks
            return;
        }
        if (add) {           // Ctrl+click: add or take away one block
            pressBlock = null;
            if (!picked.remove(p.b)) { picked.add(p.b); selected = p.b; }
            else if (selected == p.b) selected = picked.isEmpty() ? null : pickedInOrder().get(0);
            repaint();
            onSelect.accept(selected);
            return;
        }
        if (range && selected != null) {   // Shift+click: every block between, in the same list
            pressBlock = null;
            Placed a = null;
            for (Placed q : placed) if (q.b == selected) a = q;
            if (a != null && a.list == p.list) {
                List<Block> l = new ArrayList<>(p.list.subList(Math.min(a.index, p.index), Math.max(a.index, p.index) + 1));
                Block keep = selected;
                selectMany(l);
                selected = keep;
                onSelect.accept(keep);
                return;
            }
        }
        pressBlock = p.b;
        if (!picked.contains(p.b) || picked.size() == 1) select(p.b);
    }

    private void popup(Placed p, Point pt) {
        JPopupMenu m = menu.get();
        if (p != null && (p.b.type.equals("if") || p.b.type.equals("ifelse")) && picked.size() <= 1) {
            m.insert(new JPopupMenu.Separator(), 0);
            if (p.b.type.equals("if")) {
                JMenuItem addElse = new JMenuItem("Add Else");
                addElse.addActionListener(a -> { remember(); p.b.type = "ifelse"; changed(); });
                m.insert(addElse, 0);
            } else {
                JMenuItem rm = new JMenuItem("Remove Else");
                rm.addActionListener(a -> { remember(); p.b.type = "if"; p.b.elseBody.clear(); changed(); });
                m.insert(rm, 0);
            }
        }
        m.show(this, pt.x, pt.y);
    }

    private Rectangle marqueeRect() {
        return new Rectangle(Math.min(marqueeFrom.x, marqueeTo.x), Math.min(marqueeFrom.y, marqueeTo.y),
            Math.abs(marqueeFrom.x - marqueeTo.x), Math.abs(marqueeFrom.y - marqueeTo.y));
    }

    private void dragged(Point pt) {
        if (marqueeFrom != null && dragging == null) {
            marqueeTo = pt;
            Rectangle r = marqueeRect();
            List<Placed> hit = new ArrayList<>();
            for (Placed q : placed) if (r.intersects(q.head)) hit.add(q);
            hit.sort((a, b) -> Integer.compare(a.head.y, b.head.y));
            List<Block> l = new ArrayList<>();
            for (Placed q : hit) l.add(q.b);
            picked.clear();
            picked.addAll(l);
            selected = l.isEmpty() ? null : l.get(0);
            autoscroll(pt);
            repaint();
            return;
        }
        if (dragging == null) {
            if (pressBlock == null || pressAt == null || pressAt.distance(pt) < 6) return;
            // start dragging a block out of the script
            for (Placed p : placed) if (p.b == pressBlock) { dragOldList = p.list; dragOldIndex = p.index; }
            remember();
            removeFrom(script, pressBlock);
            dragging = pressBlock;
            dragFromCanvas = true;
            relayout();
        }
        dragAt = pt;
        hover = nearestInsert(pt);
        autoscroll(pt);
        repaint();
    }

    private void released(Point pt) {
        pressBlock = null;
        if (marqueeFrom != null) {
            boolean dragged = marqueeTo != null;
            marqueeFrom = null; marqueeTo = null;
            if (dragged) { onSelect.accept(selected); repaint(); }
            return;
        }
        if (dragging == null) return;
        Block b = dragging;
        dragging = null;
        Insert ins = (pt.x < 0 || pt.y < 0 || pt.x > getWidth() || pt.y > getHeight()) ? null : nearestInsert(pt);
        hover = null; dragAt = null;
        if (ins != null) { ins.list.add(Math.min(ins.index, ins.list.size()), b); select(b); }
        else if (selected == b) { selected = null; onSelect.accept(null); }   // dropped outside: deleted
        dragFromCanvas = false;
        changed();
    }

    private void cancelDrag() {
        if (dragging == null) return;
        if (dragFromCanvas && dragOldList != null) dragOldList.add(Math.min(dragOldIndex, dragOldList.size()), dragging);
        dragging = null; hover = null; dragAt = null; dragFromCanvas = false;
        if (!undo.isEmpty()) undo.remove(undo.size() - 1);
        changed();
    }

    private Insert nearestInsert(Point pt) {
        Insert best = null; double bd = Double.MAX_VALUE;
        for (Insert in : inserts) {
            if (pt.x < in.x - 40) continue;                 // left of this list: belongs to an outer list
            double d = Math.abs(pt.y - in.y) + Math.max(0, pt.x - (in.x + in.w + 60)) * 0.5 - in.x * 0.02;
            if (d < bd) { bd = d; best = in; }
        }
        return bd < 80 ? best : null;
    }

    private void autoscroll(Point pt) {
        scrollRectToVisible(new Rectangle(pt.x - 20, pt.y - 30, 40, 60));
    }

    // ------------------------------------------------------------------ drags from the palette
    /** A new block dragged from the palette is over the canvas (pt in canvas coordinates), or left it (pt null). */
    void paletteDrag(Block b, Point pt) {
        dragging = b;
        dragFromCanvas = false;
        dragAt = pt;
        hover = pt == null ? null : nearestInsert(pt);
        if (pt != null) autoscroll(pt);
        repaint();
    }

    void paletteDrop(Block b, Point pt) {
        dragging = null; dragAt = null;
        Insert ins = pt == null ? null : nearestInsert(pt);
        hover = null;
        if (ins == null) { repaint(); return; }
        remember();
        ins.list.add(Math.min(ins.index, ins.list.size()), b);
        select(b);
        changed();
    }

    /** A condition dragged from the palette: it goes onto the If or Repeat Until under the pointer. */
    void conditionDrag(String cond, Point pt) {
        dropCondition = cond;
        Block over = null;
        if (pt != null) {
            Placed p = at(pt);
            if (p != null && p.b.def().kind == BlockModel.Kind.CONDITION) over = p.b;
        }
        if (over != conditionHover) { conditionHover = over; repaint(); }
    }

    void conditionDrop(String cond, Point pt) {
        conditionDrag(cond, pt);
        Block b = conditionHover;
        conditionHover = null; dropCondition = null;
        if (b != null) {
            remember();
            b.set(0, cond);
            b.set(1, BlockModel.conditionDefault(cond));
            select(b);
            changed();
        } else repaint();
    }

    private String tipAt(Point pt) {
        Placed p = at(pt);
        if (p == null) return null;
        if (p.slot != null && p.slot.contains(pt)) return "Click to choose a target on the right";
        return p.b.def() == null ? null : p.b.def().help;
    }

    /** True when the point is on the selected block's orange slot (the editor then puts focus on the target picker). */
    boolean onSlot(Point pt) {
        for (Placed p : placed) if (p.slot != null && p.slot.contains(pt)) return true;
        return false;
    }
}
