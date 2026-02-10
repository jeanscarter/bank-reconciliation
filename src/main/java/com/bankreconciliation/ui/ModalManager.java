package com.bankreconciliation.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class ModalManager {

    private static JFrame parentFrame;
    private static JPanel backdropPanel;
    private static JPanel currentModal;
    private static float backdropOpacity = 0f;
    private static Timer fadeTimer;

    public static void init(JFrame frame) {
        parentFrame = frame;
    }

    public static void show(JPanel modal) {
        if (parentFrame == null)
            return;
        if (currentModal != null)
            dismiss(null);

        JLayeredPane layered = parentFrame.getLayeredPane();

        // Backdrop
        backdropPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(new Color(0, 0, 0, (int) (backdropOpacity * 160)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        backdropPanel.setOpaque(false);
        backdropPanel.setBounds(0, 0, layered.getWidth(), layered.getHeight());
        backdropPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dismiss(null);
            }
        });

        layered.add(backdropPanel, JLayeredPane.MODAL_LAYER);

        // Modal content
        currentModal = modal;
        Dimension prefSize = modal.getPreferredSize();
        int x = (layered.getWidth() - prefSize.width) / 2;
        int y = (layered.getHeight() - prefSize.height) / 2;
        modal.setBounds(x, y, prefSize.width, prefSize.height);
        modal.addMouseListener(new MouseAdapter() {
        }); // block click-through

        layered.add(modal, JLayeredPane.POPUP_LAYER);

        // Animate in
        backdropOpacity = 0f;
        if (fadeTimer != null)
            fadeTimer.stop();
        fadeTimer = new Timer(12, e -> {
            backdropOpacity += 0.07f;
            if (backdropOpacity >= 0.85f) {
                backdropOpacity = 0.85f;
                fadeTimer.stop();
            }
            backdropPanel.repaint();
        });
        fadeTimer.start();

        layered.revalidate();
        layered.repaint();
    }

    public static void dismiss(Runnable onComplete) {
        if (parentFrame == null || backdropPanel == null)
            return;

        if (fadeTimer != null)
            fadeTimer.stop();
        fadeTimer = new Timer(12, e -> {
            backdropOpacity -= 0.1f;
            if (backdropOpacity <= 0f) {
                backdropOpacity = 0f;
                ((Timer) e.getSource()).stop();

                JLayeredPane layered = parentFrame.getLayeredPane();
                if (currentModal != null) {
                    layered.remove(currentModal);
                    currentModal = null;
                }
                if (backdropPanel != null) {
                    layered.remove(backdropPanel);
                    backdropPanel = null;
                }
                layered.revalidate();
                layered.repaint();

                if (onComplete != null)
                    onComplete.run();
            }
            if (backdropPanel != null)
                backdropPanel.repaint();
        });
        fadeTimer.start();
    }

    public static boolean isShowing() {
        return currentModal != null;
    }
}
