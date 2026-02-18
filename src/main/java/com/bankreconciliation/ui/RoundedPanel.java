package com.bankreconciliation.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

public class RoundedPanel extends JPanel {

    private final int arc;

    private final boolean drawShadow;

    public RoundedPanel(int arc) {
        this(arc, true);
    }

    public RoundedPanel(int arc, boolean drawShadow) {
        this.arc = arc;
        this.drawShadow = drawShadow;

        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        int offset = drawShadow ? 4 : 0;

        if (drawShadow) {
            for (int i = 0; i < 4; i++) {
                g2.setColor(new Color(0, 0, 0, 12 - i * 3));
                g2.fill(new RoundRectangle2D.Float(i, i + 1, w - i * 2, h - i * 2, arc, arc));
            }
        }

        g2.setColor(getBackground());
        g2.fill(new RoundRectangle2D.Float(0, 0, w - offset, h - offset, arc, arc));

        g2.dispose();
        super.paintComponent(g);
    }
}
