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

/**
 * Overlay para revisar transacciones con diferencias mínimas (near-matches).
 * Muestra ambos lados (Libro vs Banco) con la diferencia resaltada.
 * El usuario puede aprobar cada par individualmente o aprobar todos.
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
        setPreferredSize(new Dimension(920, 600));
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
                nearMatches.size() + " par(es) con diferencias menores a Bs. 1.00 encontrado(s)");
        subtitle.setFont(new Font("Segoe UI", Font.ITALIC, 13));
        subtitle.setForeground(new Color(255, 193, 7)); // amber
        add(subtitle, "gapbottom 12");

        // Info box
        RoundedPanel infoBox = new RoundedPanel(12, false);
        infoBox.setBackground(new Color(50, 55, 65));
        infoBox.setLayout(new MigLayout("insets 12, fillx", "[grow]", ""));
        JLabel infoLabel = new JLabel(
                "<html>Las siguientes transacciones tienen montos similares pero no idénticos. "
                        + "Seleccione las que desea conciliar y presione <b>Aprobar Seleccionados</b>.</html>");
        infoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        infoLabel.setForeground(new Color(200, 200, 210));
        infoBox.add(infoLabel, "growx");
        add(infoBox, "growx, gapbottom 12");

        // Review table
        tableModel = new NearMatchTableModel();
        reviewTable = new JTable(tableModel);
        reviewTable.setRowHeight(44);
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
        reviewTable.getColumnModel().getColumn(2).setPreferredWidth(60); // Ref Libro
        reviewTable.getColumnModel().getColumn(3).setPreferredWidth(100); // Monto Libro
        reviewTable.getColumnModel().getColumn(4).setPreferredWidth(80); // Fecha Banco
        reviewTable.getColumnModel().getColumn(5).setPreferredWidth(60); // Ref Banco
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
                return c;
            }
        };
        reviewTable.getColumnModel().getColumn(3).setCellRenderer(amountRenderer);
        reviewTable.getColumnModel().getColumn(7).setCellRenderer(amountRenderer);

        // Difference renderer (highlighted in amber/red)
        DefaultTableCellRenderer diffRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setHorizontalAlignment(SwingConstants.CENTER);
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
        cancelBtn.addActionListener(e -> ModalManager.dismiss(null));
        actionRow.add(cancelBtn);

        JButton approveBtn = createStyledButton("Aprobar Seleccionados", new Color(46, 125, 50));
        approveBtn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        approveBtn.setPreferredSize(new Dimension(180, 36));
        approveBtn.addActionListener(e -> {
            int count = 0;
            for (int i = 0; i < approved.length; i++) {
                if (approved[i]) {
                    NearMatch nm = nearMatches.get(i);
                    nm.book().setStatus(Transaction.Status.OPC);
                    nm.book().setMatchedId(nm.bank().getId());
                    nm.bank().setStatus(Transaction.Status.OPC);
                    nm.bank().setMatchedId(nm.book().getId());
                    count++;
                }
            }
            int finalCount = count;
            ModalManager.dismiss(() -> {
                if (finalCount > 0) {
                    Toast.show(finalCount + " par(es) conciliado(s) ✓", Toast.Type.SUCCESS);
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
            Transaction book = nm.book();
            Transaction bank = nm.bank();
            return switch (col) {
                case 0 -> approved[row];
                case 1 -> book.getDate().toString();
                case 2 -> book.getReference();
                case 3 -> "Bs. " + FMT.format(book.getAbsAmount());
                case 4 -> bank.getDate().toString();
                case 5 -> bank.getReference();
                case 6 -> truncate(bank.getDescription(), 30);
                case 7 -> "Bs. " + FMT.format(bank.getAbsAmount());
                case 8 -> "Δ Bs. " + nm.differenceFormatted();
                default -> "";
            };
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
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
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
        btn.addActionListener(e -> ModalManager.dismiss(null));
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
        btn.setPreferredSize(new Dimension(140, 36));
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }
}
