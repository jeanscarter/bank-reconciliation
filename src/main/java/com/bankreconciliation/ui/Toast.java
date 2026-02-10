package com.bankreconciliation.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

public class Toast extends JPanel {

    public enum Type {
        SUCCESS(new Color(46, 125, 50), new Color(200, 230, 201), "✓"),
        ERROR(new Color(198, 40, 40), new Color(255, 205, 210), "✗"),
        INFO(new Color(21, 101, 192), new Color(187, 222, 251), "ℹ"),
        WARNING(new Color(245, 124, 0), new Color(255, 224, 178), "⚠");

        final Color bg;
        final Color fg;
        final String icon;

        Type(Color bg, Color fg, String icon) {
            this.bg = bg;
            this.fg = fg;
            this.icon = icon;
        }
    }

    private static final List<Toast> activeToasts = new ArrayList<>();
    private static JFrame parentFrame;

    private final String message;
    private final Type type;
    private float opacity = 0f;
    private Timer fadeInTimer;
    private Timer fadeOutTimer;
    private Timer dismissTimer;

    private Toast(String message, Type type) {
        this.message = message;
        this.type = type;
        setOpaque(false);
        setPreferredSize(new Dimension(340, 52));
        setSize(340, 52);
    }

    public static void setParentFrame(JFrame frame) {
        parentFrame = frame;
    }

    public static void show(String message, Type type) {
        if (parentFrame == null)
            return;
        Toast toast = new Toast(message, type);
        JLayeredPane layered = parentFrame.getLayeredPane();

        int yOffset = 20;
        for (Toast t : activeToasts) {
            yOffset += t.getHeight() + 10;
        }
        activeToasts.add(toast);

        int x = layered.getWidth() - toast.getWidth() - 20;
        toast.setLocation(x, yOffset);
        layered.add(toast, JLayeredPane.POPUP_LAYER);

        toast.fadeIn();
    }

    private void fadeIn() {
        fadeInTimer = new Timer(16, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                opacity += 0.08f;
                if (opacity >= 1f) {
                    opacity = 1f;
                    fadeInTimer.stop();
                    scheduleDismiss();
                }
                repaint();
            }
        });
        fadeInTimer.start();
    }

    private void scheduleDismiss() {
        dismissTimer = new Timer(2800, e -> fadeOut());
        dismissTimer.setRepeats(false);
        dismissTimer.start();
    }

    private void fadeOut() {
        fadeOutTimer = new Timer(16, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                opacity -= 0.06f;
                if (opacity <= 0f) {
                    opacity = 0f;
                    fadeOutTimer.stop();
                    remove();
                }
                repaint();
            }
        });
        fadeOutTimer.start();
    }

    private void remove() {
        JLayeredPane layered = parentFrame.getLayeredPane();
        layered.remove(this);
        layered.repaint();
        activeToasts.remove(this);
        repositionAll();
    }

    private static void repositionAll() {
        int yOffset = 20;
        JLayeredPane layered = parentFrame.getLayeredPane();
        for (Toast t : activeToasts) {
            int x = layered.getWidth() - t.getWidth() - 20;
            t.setLocation(x, yOffset);
            yOffset += t.getHeight() + 10;
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));

        RoundRectangle2D rect = new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 16, 16);

        // Shadow
        g2.setColor(new Color(0, 0, 0, 40));
        g2.fill(new RoundRectangle2D.Float(2, 3, getWidth(), getHeight(), 16, 16));

        // Background
        g2.setColor(type.bg);
        g2.fill(rect);

        // Icon
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 18));
        g2.drawString(type.icon, 16, 33);

        // Text
        g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
        g2.drawString(message, 44, 32);

        g2.dispose();
    }
}
