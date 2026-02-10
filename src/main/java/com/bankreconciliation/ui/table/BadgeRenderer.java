package com.bankreconciliation.ui.table;

import com.bankreconciliation.model.Transaction;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

public class BadgeRenderer extends DefaultTableCellRenderer {

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
            boolean isSelected, boolean hasFocus, int row, int column) {
        if (value instanceof Transaction.Status status) {
            return new BadgeComponent(status, isSelected, table);
        }
        return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
    }

    private static class BadgeComponent extends JPanel {
        private final Transaction.Status status;
        private final boolean selected;
        private final JTable table;

        BadgeComponent(Transaction.Status status, boolean selected, JTable table) {
            this.status = status;
            this.selected = selected;
            this.table = table;
            setOpaque(true);
            setBackground(selected ? table.getSelectionBackground() : table.getBackground());
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

            String label = status.getLabel();
            Font font = new Font("Segoe UI", Font.BOLD, 11);
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            int textW = fm.stringWidth(label);
            int textH = fm.getHeight();

            int badgeW = textW + 20;
            int badgeH = 24;
            int x = (getWidth() - badgeW) / 2;
            int y = (getHeight() - badgeH) / 2;

            // Badge background
            Color bgColor = status.getColor();
            g2.setColor(new Color(bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue(), 35));
            g2.fill(new RoundRectangle2D.Float(x, y, badgeW, badgeH, 12, 12));

            // Badge border
            g2.setColor(new Color(bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue(), 100));
            g2.setStroke(new BasicStroke(1.2f));
            g2.draw(new RoundRectangle2D.Float(x, y, badgeW, badgeH, 12, 12));

            // Text
            g2.setColor(bgColor);
            int textX = x + (badgeW - textW) / 2;
            int textY = y + (badgeH - textH) / 2 + fm.getAscent();
            g2.drawString(label, textX, textY);

            g2.dispose();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(80, 32);
        }
    }
}
