package codeedition;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** Dark mode look shared by the launcher and the Script Playground. */
final class Theme {
    static final Color BG = new Color(0x1e1f22);
    static final Color PANEL = new Color(0x2b2d30);
    static final Color TEXT = new Color(0xdfe1e5);
    static final Color MUTED = new Color(0x8c8f94);
    static final Color GREEN = new Color(0x4caf50);
    static final Color AMBER = new Color(0xe0a030);
    static final Color RED = new Color(0xe5534b);
    static final Color GOLD = new Color(0xcea86a);

    static void install() {
        UIManager.put("Component.arc", 8);
        UIManager.put("Button.arc", 8);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("Panel.background", BG);
        UIManager.put("TitlePane.unifiedBackground", true);
        FlatDarkLaf.setup();
        JFrame.setDefaultLookAndFeelDecorated(true);   // dark title bars too
    }

    static JButton button(String text, Color fill) {
        JButton b = new JButton(text);
        if (fill != null) {
            b.setBackground(fill);
            b.setForeground(Color.WHITE);
            b.putClientProperty("JButton.buttonType", "roundRect");
        }
        b.setFocusable(false);
        return b;
    }

    /** The Code orb (black face, white C) as window icons. */
    static List<Image> icons() {
        List<Image> list = new ArrayList<>();
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) list.add(orb(size));
        return list;
    }

    static BufferedImage orb(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double s = size;
        g.setColor(new Color(0x1e160c)); g.fill(new Ellipse2D.Double(0, 0, s, s));
        g.setColor(new Color(0x96703e)); g.fill(new Ellipse2D.Double(s * .04, s * .04, s * .92, s * .92));
        g.setColor(GOLD); g.fill(new Ellipse2D.Double(s * .08, s * .08, s * .84, s * .84));
        g.setColor(new Color(0x281c0e)); g.fill(new Ellipse2D.Double(s * .17, s * .17, s * .66, s * .66));
        g.setColor(Color.BLACK); g.fill(new Ellipse2D.Double(s * .2, s * .2, s * .6, s * .6));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke((float) (s * .1), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.draw(new java.awt.geom.Arc2D.Double(s * .32, s * .32, s * .36, s * .36, 42, 276, java.awt.geom.Arc2D.OPEN));
        g.dispose();
        return img;
    }

    /** Brings a window to the front and gives it focus, as if it had been clicked. */
    static void bringToFront(JFrame f) {
        if (!f.isVisible()) f.setVisible(true);
        if ((f.getExtendedState() & Frame.ICONIFIED) != 0) f.setExtendedState(f.getExtendedState() & ~Frame.ICONIFIED);
        f.setAlwaysOnTop(true);     // Windows only lets a background app take focus this way
        f.toFront();
        f.requestFocus();
        f.setAlwaysOnTop(false);
        f.repaint();
    }
}
