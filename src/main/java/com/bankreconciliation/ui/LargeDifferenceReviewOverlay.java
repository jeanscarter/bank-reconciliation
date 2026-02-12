package com.bankreconciliation.ui;

import com.bankreconciliation.ReconciliationEngine.NearMatch;
import com.bankreconciliation.model.Transaction;
import net.miginfocom.swing.MigLayout;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.io.FileOutputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Overlay para revisar diferencias mayores (solo lectura y exportación).
 * Estilo visual idéntico a NearMatchReviewOverlay.
 */
public class LargeDifferenceReviewOverlay extends RoundedPanel {

    private static final DecimalFormat FMT;
    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setGroupingSeparator(',');
        symbols.setDecimalSeparator('.');
        FMT = new DecimalFormat("#,##0.00", symbols);
    }

    private final List<NearMatch> largeDiffs;
    private JTable reviewTable;

    public LargeDifferenceReviewOverlay(List<NearMatch> largeDiffs) {
        super(24);
        this.largeDiffs = largeDiffs;

        setBackground(new Color(40, 44, 52));
        setPreferredSize(new Dimension(1000, 600));
        setLayout(new MigLayout("insets 28, fill, wrap", "[grow]", "[][][][grow][]"));

        buildUI();
    }

    private void buildUI() {
        // Close button
        JButton closeBtn = createCloseButton();
        add(closeBtn, "pos (100%-48) 12, w 32!, h 32!");

        // Header
        JLabel header = new JLabel("Diferencias Mayores");
        header.setFont(new Font("Segoe UI", Font.BOLD, 20));
        header.setForeground(Color.WHITE);
        add(header, "gapbottom 4");

        // Subtitle
        JLabel subtitle = new JLabel(
                largeDiffs.size() + " transacción(es) con diferencias significativas");
        subtitle.setFont(new Font("Segoe UI", Font.ITALIC, 13));
        subtitle.setForeground(new Color(255, 100, 100)); // Red-ish for warning
        add(subtitle, "gapbottom 12");

        // Info box
        RoundedPanel infoBox = new RoundedPanel(12, false);
        infoBox.setBackground(new Color(50, 55, 65));
        infoBox.setLayout(new MigLayout("insets 12, fillx", "[grow]", ""));
        JLabel infoLabel = new JLabel(
                "<html>Estas transacciones coinciden por referencia pero presentan diferencias de monto exageradas (> 1.00).<br>"
                        + "Esta vista es solo para <b>monitoreo</b>. No se pueden aprobar desde aquí.</html>");
        infoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        infoLabel.setForeground(new Color(200, 200, 210));
        infoBox.add(infoLabel, "growx");
        add(infoBox, "growx, gapbottom 12");

        // Review table
        LargeDiffTableModel tableModel = new LargeDiffTableModel();
        reviewTable = new JTable(tableModel);
        reviewTable.setRowHeight(50);
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
        // 0: Fecha Libro, 1: Ref Libro, 2: Monto Libro, 3: Fecha Banco, 4: Ref Banco,
        // 5: Desc Banco, 6: Monto Banco, 7: Diff
        reviewTable.getColumnModel().getColumn(0).setPreferredWidth(80);
        reviewTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        reviewTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        reviewTable.getColumnModel().getColumn(3).setPreferredWidth(80);
        reviewTable.getColumnModel().getColumn(4).setPreferredWidth(80);
        reviewTable.getColumnModel().getColumn(5).setPreferredWidth(140);
        reviewTable.getColumnModel().getColumn(6).setPreferredWidth(100);
        reviewTable.getColumnModel().getColumn(7).setPreferredWidth(90);

        // Renderers
        setupRenderers();

        JScrollPane scroll = new JScrollPane(reviewTable);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(new Color(40, 44, 52));
        add(scroll, "grow, gapbottom 12");

        // Action buttons
        JPanel actionRow = new JPanel(new MigLayout("insets 0, gap 12", "push[][]", ""));
        actionRow.setOpaque(false);

        JButton exportBtn = createStyledButton("Exportar Excel", new Color(46, 125, 50)); // Green
        exportBtn.addActionListener(e -> exportToExcel());
        actionRow.add(exportBtn);

        JButton closeActionBtn = createStyledButton("Cerrar", new Color(120, 120, 120));
        closeActionBtn.addActionListener(e -> ModalManager.dismiss(null));
        actionRow.add(closeActionBtn);

        add(actionRow, "growx");
    }

    private void setupRenderers() {
        // Amount renderer (right-aligned)
        DefaultTableCellRenderer amountRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setHorizontalAlignment(SwingConstants.RIGHT);
                setVerticalAlignment(SwingConstants.TOP);
                if (!isSelected)
                    setForeground(new Color(200, 200, 200));
                return c;
            }
        };
        reviewTable.getColumnModel().getColumn(2).setCellRenderer(amountRenderer);
        reviewTable.getColumnModel().getColumn(6).setCellRenderer(amountRenderer);

        // Top-align other columns
        DefaultTableCellRenderer topRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setVerticalAlignment(SwingConstants.TOP);
                return c;
            }
        };
        reviewTable.getColumnModel().getColumn(0).setCellRenderer(topRenderer);
        reviewTable.getColumnModel().getColumn(1).setCellRenderer(topRenderer);
        reviewTable.getColumnModel().getColumn(3).setCellRenderer(topRenderer);
        reviewTable.getColumnModel().getColumn(4).setCellRenderer(topRenderer);
        reviewTable.getColumnModel().getColumn(5).setCellRenderer(topRenderer);

        // Diff renderer (Bold Red)
        DefaultTableCellRenderer diffRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setHorizontalAlignment(SwingConstants.CENTER);
                setVerticalAlignment(SwingConstants.TOP);
                if (!isSelected) {
                    setForeground(new Color(255, 100, 100));
                    setFont(getFont().deriveFont(Font.BOLD));
                }
                return c;
            }
        };
        reviewTable.getColumnModel().getColumn(7).setCellRenderer(diffRenderer);
    }

    private void exportToExcel() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Guardar Reporte de Diferencias");
        fileChooser.setSelectedFile(new File("Diferencias_Mayores.xlsx"));

        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".xlsx")) {
                file = new File(file.getAbsolutePath() + ".xlsx");
            }

            try (Workbook workbook = new XSSFWorkbook()) {
                Sheet sheet = workbook.createSheet("Diferencias Mayores");

                // Styles
                CellStyle headerStyle = workbook.createCellStyle();
                org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
                headerFont.setBold(true);
                headerStyle.setFont(headerFont);

                CellStyle amountStyle = workbook.createCellStyle();
                amountStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));

                // Header
                Row headerRow = sheet.createRow(0);
                String[] headers = { "Fecha Libro", "Ref Libro", "Monto Libro", "Fecha Banco", "Ref Banco",
                        "Desc Banco", "Monto Banco", "Diferencia" };
                for (int i = 0; i < headers.length; i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers[i]);
                    cell.setCellStyle(headerStyle);
                }

                // Data
                int rowIdx = 1;
                for (NearMatch nm : largeDiffs) {
                    Row row = sheet.createRow(rowIdx++);

                    // Flatten lists for CSV-style export (simple join)
                    row.createCell(0).setCellValue(formatListSimple(nm.books(), t -> t.getDate().toString()));
                    row.createCell(1).setCellValue(formatListSimple(nm.books(), Transaction::getReference));

                    double bookTotal = nm.books().stream().mapToDouble(Transaction::getAbsAmount).sum();
                    Cell bookAmount = row.createCell(2);
                    bookAmount.setCellValue(bookTotal);
                    bookAmount.setCellStyle(amountStyle);

                    row.createCell(3).setCellValue(formatListSimple(nm.banks(), t -> t.getDate().toString()));
                    row.createCell(4).setCellValue(formatListSimple(nm.banks(), Transaction::getReference));
                    row.createCell(5).setCellValue(formatListSimple(nm.banks(), Transaction::getDescription));

                    double bankTotal = nm.banks().stream().mapToDouble(Transaction::getAbsAmount).sum();
                    Cell bankAmount = row.createCell(6);
                    bankAmount.setCellValue(bankTotal);
                    bankAmount.setCellStyle(amountStyle);

                    Cell diffCell = row.createCell(7);
                    diffCell.setCellValue(nm.difference());
                    diffCell.setCellStyle(amountStyle);
                }

                for (int i = 0; i < headers.length; i++)
                    sheet.autoSizeColumn(i);

                try (FileOutputStream fos = new FileOutputStream(file)) {
                    workbook.write(fos);
                }

                Toast.show("Exportado correctamente a Excel", Toast.Type.SUCCESS);
                ModalManager.dismiss(null);

            } catch (Exception ex) {
                ex.printStackTrace();
                Toast.show("Error al exportar: " + ex.getMessage(), Toast.Type.ERROR);
            }
        }
    }

    // ======================== Table Model ========================

    private class LargeDiffTableModel extends AbstractTableModel {
        private final String[] COLS = {
                "Fecha Libro", "Ref Libro", "Monto Libro",
                "Fecha Banco", "Ref Banco", "Desc. Banco", "Monto Banco", "Diferencia"
        };

        @Override
        public int getRowCount() {
            return largeDiffs.size();
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
        public Object getValueAt(int row, int col) {
            NearMatch nm = largeDiffs.get(row);
            List<Transaction> books = nm.books();
            List<Transaction> banks = nm.banks();

            return switch (col) {
                case 0 -> formatList(books, t -> t.getDate().toString());
                case 1 -> formatList(books, Transaction::getReference);
                case 2 -> formatAmounts(books);
                case 3 -> formatList(banks, t -> t.getDate().toString());
                case 4 -> formatList(banks, Transaction::getReference);
                case 5 -> truncate(formatList(banks, Transaction::getDescription), 40);
                case 6 -> formatAmounts(banks);
                case 7 -> "<html><br>" + "Δ Bs. " + nm.differenceFormatted() + "</html>";
                default -> "";
            };
        }

        private String formatList(List<Transaction> list, java.util.function.Function<Transaction, String> mapper) {
            if (list == null || list.isEmpty())
                return "";
            return "<html>" + list.stream().map(mapper).collect(Collectors.joining("<br>")) + "</html>";
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
    }

    // ======================== Helpers ========================

    private static String truncate(String s, int maxLen) {
        if (s == null)
            return "";
        String text = s.replace("<html>", "").replace("</html>", "").replace("<br>", " | ");
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private String formatListSimple(List<Transaction> list,
            java.util.function.Function<Transaction, String> mapper) {
        if (list == null || list.isEmpty())
            return "";
        return list.stream().map(mapper).collect(Collectors.joining(" | "));
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
                g2.drawString("✕", (getWidth() - fm.stringWidth("✕")) / 2, (getHeight() + fm.getAscent()) / 2 - 3);
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
        btn.setPreferredSize(new Dimension(160, 36));
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }
}
