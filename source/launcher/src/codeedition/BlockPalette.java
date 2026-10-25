package codeedition;

import codeedition.BlockModel.Block;
import codeedition.BlockModel.Cat;
import codeedition.BlockModel.Def;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/** The block palette: every kind of block, grouped by colour. Drag one onto the canvas, or click it to add it at the end. */
final class BlockPalette extends JComponent {
    private static final int PAD = 12, TITLE = 26;
    private final BlockCanvas canvas;
    private final List<Item> items = new ArrayList<>();
    private Cat tab = Cat.BASICS;
    private String query = "";
    private Runnable onFilter = () -> {};
    private Item pressed;
    private Point pressAt;
    private boolean dragging;

    private static final class Item {
        final Block block; final String condition; final Rectangle r; final String title;
        Item(Block block, String condition, Rectangle r, String title) { this.block = block; this.condition = condition; this.r = r; this.title = title; }
    }

    BlockPalette(BlockCanvas canvas) {
        this.canvas = canvas;
        setOpaque(true);
        build();
        MouseAdapter m = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { pressed = itemAt(e.getPoint()); pressAt = e.getPoint(); dragging = false; }
            @Override public void mouseDragged(MouseEvent e) {
                if (pressed == null || pressed.title != null) return;
                if (!dragging && pressAt.distance(e.getPoint()) < 5) return;
                dragging = true;
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                Point c = toCanvas(e);
                if (pressed.condition != null) canvas.conditionDrag(pressed.condition, c);
                else canvas.paletteDrag(dragBlock(), c);
            }
            @Override public void mouseReleased(MouseEvent e) {
                setCursor(Cursor.getDefaultCursor());
                if (pressed == null || pressed.title != null) { pressed = null; return; }
                if (dragging) {
                    Point c = toCanvas(e);
                    if (pressed.condition != null) canvas.conditionDrop(pressed.condition, c);
                    else canvas.paletteDrop(dragBlock(), c);
                } else if (pressed.condition != null) {
                    Block sel = canvas.selected();
                    if (sel != null && sel.def().kind == BlockModel.Kind.CONDITION) {
                        canvas.setTarget(sel, pressed.condition, BlockModel.conditionDefault(pressed.condition));
                    }
                } else canvas.addAtEnd(pressed.block.copy());
                pressed = null; dragBlock = null; dragging = false;
            }
            @Override public void mouseMoved(MouseEvent e) {
                Item it = itemAt(e.getPoint());
                setToolTipText(it == null ? null : it.condition != null ? "Drag onto an If or Repeat Until block" : it.block != null ? it.block.def().help : null);
                setCursor(it != null && it.title == null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        ToolTipManager.sharedInstance().registerComponent(this);
    }

    private Block dragBlock;
    private Block dragBlock() { if (dragBlock == null) dragBlock = pressed.block.copy(); return dragBlock; }

    private Point toCanvas(MouseEvent e) {
        Point p = SwingUtilities.convertPoint(this, e.getPoint(), canvas);
        Rectangle vis = canvas.getVisibleRect();
        return vis.contains(p) ? p : null;
    }

    private Item itemAt(Point p) {
        for (Item it : items) if (it.r.contains(p)) return it;
        return null;
    }

    void onFilter(Runnable r) { onFilter = r; }

    /** The tab buttons and search box that go above the palette. */
    JComponent header() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(new Color(0x1e1f22));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "Search blocks");
        JPanel tabs = new JPanel(new GridLayout(0, 2, 4, 4));
        tabs.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        for (Cat c : Cat.values()) {
            JToggleButton b = new JToggleButton(c.title);
            b.setFont(b.getFont().deriveFont(10.5f));
            b.setFocusable(false);
            b.setMargin(new Insets(2, 4, 2, 4));
            b.setSelected(c == tab);
            b.setForeground(c.color.brighter());
            b.addActionListener(e -> { tab = c; search.setText(""); query = ""; refilter(); });
            group.add(b);
            tabs.add(b);
        }
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { query = search.getText().trim().toLowerCase(); refilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { query = search.getText().trim().toLowerCase(); refilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) {}
        });
        p.add(search, BorderLayout.NORTH);
        p.add(tabs, BorderLayout.CENTER);
        return p;
    }

    private void refilter() { build(); revalidate(); repaint(); onFilter.run(); }

    private boolean shows(Def d) {
        if (d.type.equals("jsfile")) return false;     // only made by reading a plain JavaScript file
        if (!query.isEmpty()) return d.label.toLowerCase().contains(query) || d.help.toLowerCase().contains(query);
        return d.cat == tab;
    }

    private void build() {
        items.clear();
        Graphics2D g = BlockCanvas.MEASURE;
        int y = PAD, w = 0;
        for (Cat cat : Cat.values()) {
            if (cat == Cat.CONDITION) continue;
            boolean any = false;
            for (Def d : BlockModel.DEFS) if (d.cat == cat && shows(d)) any = true;
            if (!any) continue;
            items.add(new Item(null, null, new Rectangle(PAD, y, 150, TITLE), cat.title));
            y += TITLE;
            for (Def d : BlockModel.DEFS) {
                if (d.cat != cat || !shows(d)) continue;
                Block b = new Block(d.type);
                int bw = BlockCanvas.compactWidth(g, b);
                int bh = d.shape == BlockModel.Shape.PLAIN ? BlockCanvas.ROW : BlockCanvas.ROW + BlockCanvas.GAP + BlockCanvas.EMPTY + BlockCanvas.FOOT
                    + (d.shape == BlockModel.Shape.IF_ELSE ? BlockCanvas.ELSE_ROW + BlockCanvas.EMPTY : 0);
                items.add(new Item(b, null, new Rectangle(PAD, y, bw, bh), null));
                w = Math.max(w, bw);
                y += bh + 8;
            }
            y += 8;
        }
        List<String> conds = new ArrayList<>();
        for (String c : BlockModel.CONDITIONS) if (query.isEmpty() ? tab == Cat.CONDITION || tab == Cat.LOGIC : c.toLowerCase().contains(query)) conds.add(c);
        if (!conds.isEmpty()) {
            items.add(new Item(null, null, new Rectangle(PAD, y, 150, TITLE), Cat.CONDITION.title));
            y += TITLE;
        }
        if (items.isEmpty()) {
            items.add(new Item(null, null, new Rectangle(PAD, y, 150, TITLE), "No blocks match"));
            y += TITLE;
        }
        g.setFont(BlockCanvas.SLOT);
        for (String c : conds) {
            int cw = g.getFontMetrics().stringWidth(c) + 28;
            items.add(new Item(null, c, new Rectangle(PAD, y, cw, 26), null));
            w = Math.max(w, cw);
            y += 32;
        }
        setPreferredSize(new Dimension(w + PAD * 2, y + PAD));
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x1e1f22));
        g.fillRect(0, 0, getWidth(), getHeight());
        for (Item it : items) {
            if (it.title != null) {
                g.setFont(BlockCanvas.SLOT.deriveFont(11f));
                g.setColor(new Color(0x8c8f94));
                g.drawString(it.title.toUpperCase(), it.r.x, it.r.y + 17);
            } else if (it.condition != null) {
                Color c = Cat.CONDITION.color;
                g.setColor(c.darker().darker());
                g.fillRoundRect(it.r.x, it.r.y, it.r.width, it.r.height, 26, 26);
                g.setColor(c);
                g.drawRoundRect(it.r.x, it.r.y, it.r.width, it.r.height, 26, 26);
                g.setFont(BlockCanvas.SLOT);
                g.setColor(new Color(0xffe0a8));
                g.drawString(it.condition, it.r.x + 14, it.r.y + 17);
            } else {
                BlockCanvas.paintFloating(g, it.block, it.r.x, it.r.y, true);
            }
        }
        g.dispose();
    }
}
