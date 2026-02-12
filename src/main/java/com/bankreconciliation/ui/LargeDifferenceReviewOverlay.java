package com.bankreconciliation.ui;

import com.bankreconciliation.ReconciliationEngine.NearMatch;
import com.bankreconciliation.model.Transaction;
import com.bankreconciliation.ui.table.CurrencyRenderer;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.List;

public class LargeDifferenceReviewOverlay extends JPanel {

    public LargeDifferenceReviewOverlay(List<NearMatch> largeDiffs) {
        setOpaque(false);
        setLayout(new GridBagLayout());

        // ── Card Container ──
        RoundedPanel card = new RoundedPanel(20);
        card.setBackground(new Color(45, 50, 58));
        card.setLayout(new MigLayout("insets 24, fill, wrap", "[grow]", "[][grow][]"));
        card.setPreferredSize(new Dimension(900, 600));

        // ── Header ──
        JPanel header = new JPanel(new MigLayout("insets 0, fillx", "[]push[]", ""));
        header.setOpaque(false);

        JLabel titleLabel = new JLabel("⚠️  Diferencias Mayores a 1.00");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        titleLabel.setForeground(Color.WHITE);
        header.add(titleLabel);

        JButton closeIconBtn = new JButton("✕");
        closeIconBtn.setFont(new Font("Segoe UI", Font.BOLD, 16));
        closeIconBtn.setForeground(new Color(160, 160, 170));
        closeIconBtn.setContentAreaFilled(false);
        closeIconBtn.setBorderPainted(false);
        closeIconBtn.setFocusPainted(false);
        closeIconBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeIconBtn.addActionListener(e -> ModalManager.dismiss(null));
        header.add(closeIconBtn);

        card.add(header, "growx");

        // ── Info Label ──
        JLabel infoLabel = new JLabel(
                "Estas transacciones coinciden por REFERENCIA pero la diferencia de monto es mayor a 1.00. No se pueden aprobar automáticamente.");
        infoLabel.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        infoLabel.setForeground(new Color(200, 200, 200));
        card.add(infoLabel, "growx, gaptop 5");

        // ── Table ──
        JTable table = new JTable(new LargeDiffTableModel(largeDiffs));
        styleTable(table);

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(new Color(45, 50, 58));
        card.add(scrollPane, "grow");

        // ── Footer ──
        JPanel footer = new JPanel(new MigLayout("insets 10 0 0 0, fillx", "push[]", ""));
        footer.setOpaque(false);

        JButton closeBtn = new JButton("Cerrar");
        closeBtn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        closeBtn.setForeground(Color.WHITE);
        closeBtn.setBackground(new Color(60, 63, 65));
        closeBtn.setFocusPainted(false);
        closeBtn.setPreferredSize(new Dimension(100, 30));
        closeBtn.addActionListener(e -> ModalManager.dismiss(null));
        footer.add(closeBtn);

        card.add(footer, "growx");

        add(card);
    }

    private void styleTable(JTable table) {
        table.setRowHeight(30);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        table.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
        table.getTableHeader().setOpaque(false);
        table.getTableHeader().setBackground(new Color(60, 63, 65));
        table.getTableHeader().setForeground(Color.WHITE);
        table.setFillsViewportHeight(true);

        // Custom Renderers
        table.getColumnModel().getColumn(3).setCellRenderer(new CurrencyRenderer(false)); // Book Amount
        table.getColumnModel().getColumn(4).setCellRenderer(new CurrencyRenderer(false)); // Bank Amount
        table.getColumnModel().getColumn(5).setCellRenderer(new CurrencyRenderer(true)); // Diff (Red/Green)

        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        table.getColumnModel().getColumn(0).setCellRenderer(centerRenderer);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(new Color(0, 0, 0, 180));
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.dispose();
        super.paintComponent(g);
    }

    // ── Table Model ──

    private static class LargeDiffTableModel extends AbstractTableModel {
        private final List<NearMatch> data;
        private final String[] columns = { "Ref", "Fecha Libro", "Fecha Banco", "Monto Libro", "Monto Banco",
                "Diferencia" };

        public LargeDiffTableModel(List<NearMatch> data) {
            this.data = data;
        }

        @Override
        public int getRowCount() {
            return data.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int col) {
            return columns[col];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            NearMatch match = data.get(rowIndex);
            Transaction book = match.books().get(0); // Assuming 1:1 for now or 1st of group
            Transaction bank = match.banks().get(0);

            return switch (columnIndex) {
                case 0 -> book.getReference();
                case 1 -> book.getDate();
                case 2 -> bank.getDate();
                case 3 -> book.getAbsAmount();
                case 4 -> bank.getAbsAmount();
                case 5 -> match.difference();
                default -> "";
            };
        }
    }
}
