package com.bankreconciliation.ui;

import com.bankreconciliation.ReconciliationEngine.NearMatch;
import com.bankreconciliation.model.Transaction;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Overlay para revisar transacciones con diferencias mínimas (near-matches).
 * Muestra ambos lados (Libro vs Banco) con la diferencia resaltada.
 * Soporta coincidencias de Uno-a-Uno y Muchos-a-Uno.
 */
public class NearMatchReviewOverlay extends RoundedPanel {

    private static final DecimalFormat FMT;
    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setGroupingSeparator(',');
        symbols.setDecimalSeparator('.');
        FMT = new DecimalFormat("#,##0.00", symbols);
    }

    private final List<NearMatch> nearMatches;
    private final Runnable onComplete;
    private final boolean[] approved;
    private JTable reviewTable;
    private NearMatchTableModel tableModel;

    public NearMatchReviewOverlay(List<NearMatch> nearMatches, Runnable onComplete) {
        super(24);
        this.nearMatches = nearMatches;
        this.onComplete = onComplete;
        this.approved = new boolean[nearMatches.size()];

        setBackground(new Color(40, 44, 52));
        setPreferredSize(new Dimension(1000, 600)); // Wider for groups
        setLayout(new MigLayout("insets 28, fill, wrap", "[grow]", "[][][][grow][]"));

        buildUI();
    }

    private void buildUI() {
        // Close button
        JButton closeBtn = createCloseButton();
        add(closeBtn, "pos (100%-48) 12, w 32!, h 32!");

        // Header
        JLabel header = new JLabel("Revisión de Coincidencias Aproximadas");
        header.setFont(new Font("Segoe UI", Font.BOLD, 20));
        header.setForeground(Color.WHITE);
        add(header, "gapbottom 4");

        // Subtitle
        JLabel subtitle = new JLabel(
                nearMatches.size() + " grupo(s) con diferencias encontradas");
        subtitle.setFont(new Font("Segoe UI", Font.ITALIC, 13));
        subtitle.setForeground(new Color(255, 193, 7)); // amber
        add(subtitle, "gapbottom 12");

        // Info box
        RoundedPanel infoBox = new RoundedPanel(12, false);
        infoBox.setBackground(new Color(50, 55, 65));
        infoBox.setLayout(new MigLayout("insets 12, fillx", "[grow]", ""));
        JLabel infoLabel = new JLabel(
                "<html>Se encontraron posibles coincidencias, incluyendo <b>agrupaciones por referencia</b> (ej: 17013, 17013-1).<br>"
                        + "Revise los montos sumados y apruebe si corresponden.</html>");
        infoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        infoLabel.setForeground(new Color(200, 200, 210));
        infoBox.add(infoLabel, "growx");
        add(infoBox, "growx, gapbottom 12");

        // Review table
        tableModel = new NearMatchTableModel();
        reviewTable = new JTable(tableModel);
        reviewTable.setRowHeight(60); // Taller rows for potential groups
        reviewTable.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        reviewTable.setShowGrid(false);
        reviewTable.setIntercellSpacing(new Dimension(0, 1));
        reviewTable.setFillsViewportHeight(true);
        reviewTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        reviewTable.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 11));
        reviewTable.getTableHeader().setReorderingAllowed(false);
        reviewTable.putClientProperty("FlatLaf.style",
                "showHorizontalLines: true; showVerticalLines: false; " +
                        "cellFocusColor: #00000000; selectionBackground: #2d3748; selectionForeground: #ffffff");

        // Column widths
        reviewTable.getColumnModel().getColumn(0).setPreferredWidth(35); // ✓
        reviewTable.getColumnModel().getColumn(1).setPreferredWidth(80); // Fecha Libro
        reviewTable.getColumnModel().getColumn(2).setPreferredWidth(80); // Ref Libro
        reviewTable.getColumnModel().getColumn(3).setPreferredWidth(100); // Monto Libro
        reviewTable.getColumnModel().getColumn(4).setPreferredWidth(80); // Fecha Banco
        reviewTable.getColumnModel().getColumn(5).setPreferredWidth(80); // Ref Banco
        reviewTable.getColumnModel().getColumn(6).setPreferredWidth(140); // Desc Banco
        reviewTable.getColumnModel().getColumn(7).setPreferredWidth(100); // Monto Banco
        reviewTable.getColumnModel().getColumn(8).setPreferredWidth(80); // Diferencia

        // Amount renderer (right-aligned with color)
        DefaultTableCellRenderer amountRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setHorizontalAlignment(SwingConstants.RIGHT);
                if (!isSelected) {
                    setForeground(new Color(76, 175, 80));
                }
                setVerticalAlignment(SwingConstants.TOP); // Alignment top for multi-line
                return c;
            }
        };
        reviewTable.getColumnModel().getColumn(3).setCellRenderer(amountRenderer);
        reviewTable.getColumnModel().getColumn(7).setCellRenderer(amountRenderer);

        // Top-align other text columns for better readability of groups
        DefaultTableCellRenderer topRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setVerticalAlignment(SwingConstants.TOP);
                return c;
            }
        };
        reviewTable.getColumnModel().getColumn(1).setCellRenderer(topRenderer);
        reviewTable.getColumnModel().getColumn(2).setCellRenderer(topRenderer);
        reviewTable.getColumnModel().getColumn(4).setCellRenderer(topRenderer);
        reviewTable.getColumnModel().getColumn(5).setCellRenderer(topRenderer);
        reviewTable.getColumnModel().getColumn(6).setCellRenderer(topRenderer);

        // Difference renderer (highlighted in amber/red)
        DefaultTableCellRenderer diffRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setHorizontalAlignment(SwingConstants.CENTER);
                setVerticalAlignment(SwingConstants.TOP);
                if (!isSelected) {
                    setForeground(new Color(255, 152, 0)); // amber
                    setFont(getFont().deriveFont(Font.BOLD));
                }
                return c;
            }
        };
        reviewTable.getColumnModel().getColumn(8).setCellRenderer(diffRenderer);

        JScrollPane scroll = new JScrollPane(reviewTable);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(new Color(40, 44, 52));
        add(scroll, "grow, gapbottom 12");

        // Action buttons
        JPanel actionRow = new JPanel(new MigLayout("insets 0, gap 12", "[]push[][][]", ""));
        actionRow.setOpaque(false);

        // Select All checkbox
        JCheckBox selectAll = new JCheckBox("Seleccionar Todos");
        selectAll.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        selectAll.setForeground(new Color(200, 200, 210));
        selectAll.setOpaque(false);
        selectAll.addActionListener(e -> {
            boolean selected = selectAll.isSelected();
            for (int i = 0; i < approved.length; i++) {
                approved[i] = selected;
            }
            tableModel.fireTableDataChanged();
        });
        actionRow.add(selectAll);

        JButton cancelBtn = createStyledButton("Cancelar", new Color(120, 120, 120));
        cancelBtn.addActionListener(e -> ModalManager.dismiss(onComplete));
        actionRow.add(cancelBtn);

        JButton approveBtn = createStyledButton("Aprobar Seleccionados", new Color(46, 125, 50));
        approveBtn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        approveBtn.setPreferredSize(new Dimension(180, 36));
        approveBtn.addActionListener(e -> {
            int count = 0;
            for (int i = 0; i < approved.length; i++) {
                if (approved[i]) {
                    NearMatch nm = nearMatches.get(i);
                    // Approve all Book transactions in group
                    for (Transaction book : nm.books()) {
                        book.setStatus(Transaction.Status.OPC);
                        // If 1-to-1, link ID. If Many-to-One, link to the first Bank ID (or -1?)
                        // Ideally link to the primary bank ID.
                        if (!nm.banks().isEmpty()) {
                            book.setMatchedId(nm.banks().get(0).getId());
                        }
                    }
                    // Approve all Bank transactions in group
                    for (Transaction bank : nm.banks()) {
                        bank.setStatus(Transaction.Status.OPC);
                        if (!nm.books().isEmpty()) {
                            bank.setMatchedId(nm.books().get(0).getId()); // Link to first book
                        }
                    }
                    count++;
                }
            }
            int finalCount = count;
            ModalManager.dismiss(() -> {
                if (finalCount > 0) {
                    Toast.show(finalCount + " grupo(s) conciliado(s) ✓", Toast.Type.SUCCESS);
                }
                if (onComplete != null)
                    onComplete.run();
            });
        });
        actionRow.add(approveBtn);

        add(actionRow, "growx");
    }

    // ======================== Table Model ========================

    private class NearMatchTableModel extends AbstractTableModel {
        private final String[] COLS = {
                "✓", "Fecha Libro", "Ref Libro", "Monto Libro",
                "Fecha Banco", "Ref Banco", "Desc. Banco", "Monto Banco", "Diferencia"
        };

        @Override
        public int getRowCount() {
            return nearMatches.size();
        }

        @Override
        public int getColumnCount() {
            return COLS.length;
        }

        @Override
        public String getColumnName(int col) {
            return COLS[col];
        }

        @Override
        public Class<?> getColumnClass(int col) {
            return col == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            return col == 0; // Only checkbox is editable
        }

        @Override
        public Object getValueAt(int row, int col) {
            NearMatch nm = nearMatches.get(row);
            List<Transaction> books = nm.books();
            List<Transaction> banks = nm.banks();

            return switch (col) {
                case 0 -> approved[row];

                // BOOK SIDE
                case 1 -> formatList(books, t -> t.getDate().toString());
                case 2 -> formatList(books, Transaction::getReference);
                case 3 -> formatAmounts(books);

                // BANK SIDE
                case 4 -> formatList(banks, t -> t.getDate().toString());
                case 5 -> formatList(banks, Transaction::getReference);
                case 6 -> truncate(formatList(banks, Transaction::getDescription), 40);
                case 7 -> formatAmounts(banks);

                case 8 -> "<html><br>" + "Δ Bs. " + nm.differenceFormatted() + "</html>";
                default -> "";
            };
        }

        private String formatList(List<Transaction> list, java.util.function.Function<Transaction, String> mapper) {
            if (list == null || list.isEmpty())
                return "";
            return "<html>" + list.stream()
                    .map(mapper)
                    .collect(Collectors.joining("<br>")) + "</html>";
        }

        private String formatAmounts(List<Transaction> list) {
            if (list == null || list.isEmpty())
                return "";
            String detail = list.stream()
                    .map(t -> "Bs. " + FMT.format(t.getAbsAmount()))
                    .collect(Collectors.joining("<br>"));

            if (list.size() > 1) {
                double total = list.stream().mapToDouble(Transaction::getAbsAmount).sum();
                detail += "<br><b>Total: Bs. " + FMT.format(total) + "</b>";
            }
            return "<html>" + detail + "</html>";
        }

        @Override
        public void setValueAt(Object value, int row, int col) {
            if (col == 0 && value instanceof Boolean b) {
                approved[row] = b;
                fireTableCellUpdated(row, col);
            }
        }
    }

    // ======================== Helpers ========================

    private static String truncate(String s, int maxLen) {
        if (s == null)
            return "";
        // Simple truncation for HTML content is risky, but assuming simple text
        String text = s.replace("<html>", "").replace("</html>", "").replace("<br>", " | ");
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private JButton createCloseButton() {
        JButton btn = new JButton("✕") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (getModel().isRollover()) {
                    g2.setColor(new Color(244, 67, 54, 40));
                    g2.fillOval(0, 0, getWidth(), getHeight());
                }
                g2.setColor(new Color(180, 180, 180));
                g2.setFont(new Font("Segoe UI", Font.BOLD, 16));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString("✕", (getWidth() - fm.stringWidth("✕")) / 2,
                        (getHeight() + fm.getAscent()) / 2 - 3);
                g2.dispose();
            }
        };
        btn.setPreferredSize(new Dimension(32, 32));
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addActionListener(e -> ModalManager.dismiss(onComplete));
        return btn;
    }

    private JButton createStyledButton(String text, Color baseColor) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = getModel().isRollover() ? baseColor.brighter() : baseColor;
                if (getModel().isPressed())
                    bg = baseColor.darker();
                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 14, 14));
                g2.setColor(Color.WHITE);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                        (getHeight() + fm.getAscent()) / 2 - 3);
                g2.dispose();
            }
        };
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setPreferredSize(new Dimension(180, 36));
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }
}
