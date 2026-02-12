package com.bankreconciliation.report;

import com.bankreconciliation.model.Transaction;

import javax.swing.*;
import javax.swing.border.MatteBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Genera el panel del Reporte "Resumen - Conciliación Bancaria"
 * con estilo similar al Excel original (fondo claro, tablas con bordes).
 */
public class ReconciliationReportGenerator {

    private static final DecimalFormat FMT;
    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setGroupingSeparator(',');
        symbols.setDecimalSeparator('.');
        FMT = new DecimalFormat("#,##0.00", symbols);
    }

    // ────────── Report Data Model ──────────

    public static class ReportData {
        // Libro Contable
        public double libroSaldoInicial;
        public double libroTotalDebe;
        public double libroTotalHaber;
        public double libroSaldoFinal;

        // Extracto Bancario
        public double bancoSaldoInicial;
        public double bancoTotalDebe; // Depósitos
        public double bancoTotalHaber; // Retiros
        public double bancoSaldoFinal;

        // Diferencias por tipo
        public double totalDNA;
        public double totalRNE;
        public double totalANR;
        public double totalCNR;
        public double diferencia;
        public double saldoPorConciliar;

        // Detalle de no registrados
        public List<Transaction> dnaTransactions = new ArrayList<>();
        public List<Transaction> rneTransactions = new ArrayList<>();
        public List<Transaction> anrTransactions = new ArrayList<>();
        public List<Transaction> cnrTransactions = new ArrayList<>();

        // Cargos Recurrentes
        public List<RecurringCharge> recurringCharges = new ArrayList<>();
    }

    // ────────── Calculate Report ──────────

    public static ReportData calculate(List<Transaction> bookTransactions,
            List<Transaction> bankTransactions,
            double saldoInicial) {
        ReportData data = new ReportData();

        // ── Libro Contable ──
        data.libroSaldoInicial = saldoInicial;
        double libroTotalDebe = 0;
        double libroTotalHaber = 0;

        for (Transaction t : bookTransactions) {
            libroTotalDebe += t.getDeposit();
            libroTotalHaber += t.getWithdrawal();
        }
        data.libroTotalDebe = libroTotalDebe;
        data.libroTotalHaber = libroTotalHaber;
        data.libroSaldoFinal = saldoInicial + libroTotalDebe - libroTotalHaber;

        // ── Extracto Bancario ──
        data.bancoSaldoInicial = saldoInicial;
        double bancoTotalDebe = 0;
        double bancoTotalHaber = 0;

        for (Transaction t : bankTransactions) {
            bancoTotalDebe += t.getDeposit();
            bancoTotalHaber += t.getWithdrawal();
        }
        data.bancoTotalDebe = bancoTotalDebe;
        data.bancoTotalHaber = bancoTotalHaber;
        data.bancoSaldoFinal = saldoInicial + bancoTotalDebe - bancoTotalHaber;

        // ── Clasificar no registrados ──
        for (Transaction t : bookTransactions) {
            switch (t.getStatus()) {
                case DNA -> {
                    data.totalDNA += t.getAbsAmount();
                    data.dnaTransactions.add(t);
                }
                case RNE -> {
                    data.totalRNE += t.getAbsAmount();
                    data.rneTransactions.add(t);
                }
                default -> {
                }
            }
        }
        for (Transaction t : bankTransactions) {
            switch (t.getStatus()) {
                case ANR -> {
                    data.totalANR += t.getAbsAmount();
                    data.anrTransactions.add(t);
                }
                case CNR -> {
                    data.totalCNR += t.getAbsAmount();
                    data.cnrTransactions.add(t);
                }
                default -> {
                }
            }
        }

        data.diferencia = data.libroSaldoFinal - data.bancoSaldoFinal;
        data.saldoPorConciliar = data.diferencia
                - data.totalDNA
                + data.totalRNE
                + data.totalANR
                - data.totalCNR;

        // ── Agrupar Cargos Recurrentes (CNR) ──
        data.recurringCharges = new ArrayList<>();
        java.util.Map<String, Double> recurringMap = com.bankreconciliation.util.RecurringChargesCalculator
                .calculate(data.cnrTransactions);

        for (java.util.Map.Entry<String, Double> entry : recurringMap.entrySet()) {
            if (entry.getValue() > 0) {
                data.recurringCharges.add(new RecurringCharge(entry.getKey(), entry.getValue()));
            }
        }

        return data;
    }

    public static class RecurringCharge {
        String concept;
        double amount;

        public RecurringCharge(String concept, double amount) {
            this.concept = concept;
            this.amount = amount;
        }
    }

    // ────────── Build Report Panel ──────────

    public static JPanel buildReportPanel(ReportData data) {
        JPanel root = new JPanel(new GridBagLayout());
        root.setBackground(Color.WHITE);
        root.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(0, 0, 0, 20);

        // Left: Summary
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.40;
        gbc.weighty = 1.0;
        root.add(buildSummarySection(data), gbc);

        // Right container (Detail + Recurring)
        gbc.gridx = 1;
        gbc.weightx = 0.60;
        gbc.insets = new Insets(0, 0, 0, 0);

        JPanel rightPanel = new JPanel();
        rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS));
        rightPanel.setBackground(Color.WHITE);

        rightPanel.add(buildDetailSection(data));

        if (!data.recurringCharges.isEmpty()) {
            rightPanel.add(Box.createVerticalStrut(20));
            rightPanel.add(buildRecurringSection(data));
        }

        root.add(rightPanel, gbc);

        return root;
    }

    // ════════════════ LEFT: SUMMARY ════════════════

    private static JPanel buildSummarySection(ReportData data) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(Color.WHITE);

        // Title banner
        JPanel titleBanner = new JPanel();
        titleBanner.setBackground(new Color(0, 100, 80));
        titleBanner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        titleBanner.setPreferredSize(new Dimension(500, 40));
        JLabel titleLabel = new JLabel("RESUMEN - CONCILIACIÓN BANCARIA");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        titleLabel.setForeground(Color.WHITE);
        titleBanner.add(titleLabel);
        panel.add(titleBanner);
        panel.add(Box.createVerticalStrut(16));

        // ── Side-by-side: Libro Contable vs Extracto Bancario ──
        JPanel sideBySide = new JPanel(new GridLayout(1, 2, 24, 0));
        sideBySide.setBackground(Color.WHITE);
        sideBySide.setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));

        sideBySide.add(buildAccountBlock("📘  LIBRO CONTABLE BANCOS",
                data.libroSaldoInicial, data.libroTotalDebe, data.libroTotalHaber, data.libroSaldoFinal));
        sideBySide.add(buildAccountBlock("🏦  EXTRACTO BANCARIO",
                data.bancoSaldoInicial, data.bancoTotalDebe, data.bancoTotalHaber, data.bancoSaldoFinal));

        panel.add(sideBySide);
        panel.add(Box.createVerticalStrut(20));

        // ── Diferencia breakdown ──
        JPanel diffPanel = new JPanel();
        diffPanel.setLayout(new BoxLayout(diffPanel, BoxLayout.Y_AXIS));
        diffPanel.setBackground(Color.WHITE);
        diffPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));

        addDiffRow(diffPanel, "Diferencia Libro Bancos - Extracto Bancario", data.diferencia, false, false);
        addDiffRow(diffPanel, "(-) DNA - Depósito no abonado en cuenta bancaria", -data.totalDNA, false, true);
        addDiffRow(diffPanel, "(+) RNE - Retiro no efectuado en CB o cheque no cobrado", data.totalRNE, false, false);
        addDiffRow(diffPanel, "(+) ANR - Abono en CB no registrado en Libro Bancos", data.totalANR, false, false);
        addDiffRow(diffPanel, "(-) CNR - Cargo en CB no registrado en Libro Bancos", -data.totalCNR, false, true);

        panel.add(diffPanel);
        panel.add(Box.createVerticalStrut(12));

        // ── Saldo por Conciliar ──
        JPanel saldoBar = new JPanel();
        saldoBar.setBackground(new Color(0, 100, 80));
        saldoBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        saldoBar.setPreferredSize(new Dimension(500, 36));
        saldoBar.setLayout(new BorderLayout());

        JLabel saldoLabel = new JLabel("  SALDO POR CONCILIAR");
        saldoLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        saldoLabel.setForeground(Color.WHITE);
        saldoBar.add(saldoLabel, BorderLayout.WEST);

        JLabel saldoValue = new JLabel(formatSigned(data.saldoPorConciliar) + "  ");
        saldoValue.setFont(new Font("Segoe UI", Font.BOLD, 12));
        saldoValue.setForeground(Math.abs(data.saldoPorConciliar) < 0.01 ? Color.WHITE : new Color(255, 80, 80));
        saldoValue.setHorizontalAlignment(SwingConstants.RIGHT);
        saldoBar.add(saldoValue, BorderLayout.EAST);

        panel.add(saldoBar);

        return panel;
    }

    private static JPanel buildAccountBlock(String title, double saldoInicial,
            double totalDebe, double totalHaber, double saldoFinal) {
        JPanel block = new JPanel();
        block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
        block.setBackground(Color.WHITE);

        // Title
        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        titleRow.setBackground(new Color(245, 245, 245));
        titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        JLabel tLabel = new JLabel(title);
        tLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
        tLabel.setForeground(new Color(50, 50, 50));
        titleRow.add(tLabel);
        block.add(titleRow);

        // Rows
        addAccountRow(block, "SALDO INICIAL", saldoInicial, false, false);
        addAccountRow(block, "TOTAL DEBE", totalDebe, false, false);
        addAccountRow(block, "TOTAL HABER", totalHaber, true, false);
        addAccountRow(block, "SALDO FINAL", saldoFinal, false, true);

        return block;
    }

    private static void addAccountRow(JPanel parent, String label, double value,
            boolean isNegative, boolean highlight) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(highlight ? new Color(230, 255, 230) : Color.WHITE);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        row.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

        JLabel lbl = new JLabel(label);
        lbl.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        lbl.setForeground(new Color(60, 60, 60));
        row.add(lbl, BorderLayout.WEST);

        String formattedValue = isNegative ? "(" + FMT.format(Math.abs(value)) + ")" : FMT.format(value);
        JLabel val = new JLabel(formattedValue);
        val.setFont(new Font("Segoe UI", highlight ? Font.BOLD : Font.PLAIN, 11));
        val.setForeground(isNegative ? new Color(200, 50, 50) : new Color(40, 40, 40));
        val.setHorizontalAlignment(SwingConstants.RIGHT);
        row.add(val, BorderLayout.EAST);

        parent.add(row);
    }

    private static void addDiffRow(JPanel parent, String label, double value,
            boolean highlight, boolean isNegative) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(Color.WHITE);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        row.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(0, 0, 1, 0, new Color(220, 220, 220)),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));

        JLabel lbl = new JLabel(label);
        lbl.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        lbl.setForeground(new Color(60, 60, 60));
        row.add(lbl, BorderLayout.WEST);

        String formatted;
        if (isNegative && value < 0) {
            formatted = "(" + FMT.format(Math.abs(value)) + ")";
        } else {
            formatted = FMT.format(value);
        }
        JLabel val = new JLabel(formatted);
        val.setFont(new Font("Segoe UI", Font.BOLD, 11));
        val.setForeground(value < -0.001 ? new Color(200, 50, 50) : new Color(40, 40, 40));
        val.setHorizontalAlignment(SwingConstants.RIGHT);
        row.add(val, BorderLayout.EAST);

        parent.add(row);
    }

    // ════════════════ RIGHT: DETAIL (Top) + RECURRING (Bottom) ════════════════

    private static JPanel buildDetailSection(ReportData data) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(Color.WHITE);

        // Title
        JPanel titleBanner = new JPanel();
        titleBanner.setBackground(new Color(0, 100, 80));
        titleBanner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        titleBanner.setPreferredSize(new Dimension(500, 40));
        JLabel titleLabel = new JLabel("DETALLE DE NO REGISTRADOS");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        titleLabel.setForeground(Color.WHITE);
        titleBanner.add(titleLabel);
        panel.add(titleBanner);
        panel.add(Box.createVerticalStrut(8));

        // Build combined list
        List<DetailRow> rows = new ArrayList<>();

        addGroupToRows(rows, "(-) DNA - Depósito no abonado en cuenta bancaria", data.dnaTransactions);
        addGroupToRows(rows, "(+) RNE - Retiro no efectuado en CB o cheque no cobrado", data.rneTransactions);
        addGroupToRows(rows, "(+) ANR - Abono en CB no registrado en Libro Bancos", data.anrTransactions);
        addGroupToRows(rows, "(-) CNR - Cargo en CB no registrado en Libro Bancos", data.cnrTransactions);

        // Table
        String[] columns = { "NRO OPERACIÓN", "FECHA", "DESCRIPCIÓN", "MONTO" };
        DetailTableModel model = new DetailTableModel(rows, columns);
        JTable table = new JTable(model);

        // ── Table Styling ──
        table.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        table.setRowHeight(22);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(true);
        table.setGridColor(new Color(200, 200, 200));
        table.setBackground(Color.WHITE);
        table.setForeground(new Color(40, 40, 40));
        table.setSelectionBackground(new Color(220, 235, 255));

        // Header style
        table.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 11));
        table.getTableHeader().setBackground(new Color(0, 100, 80));
        table.getTableHeader().setForeground(Color.WHITE);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setBorder(new MatteBorder(1, 1, 1, 1, new Color(0, 80, 60)));

        // Column widths
        table.getColumnModel().getColumn(0).setPreferredWidth(90);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(220);
        table.getColumnModel().getColumn(3).setPreferredWidth(80);

        // Amount renderer (right-aligned)
        DefaultTableCellRenderer rightRenderer = new DefaultTableCellRenderer();
        rightRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(3).setCellRenderer(rightRenderer);

        // Category row renderer
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column);
                DetailRow dr = rows.get(row);
                if (dr.isHeader) {
                    c.setBackground(new Color(240, 240, 240));
                    c.setFont(new Font("Segoe UI", Font.BOLD, 11));
                    c.setForeground(new Color(40, 40, 40));
                } else if (!isSelected) {
                    c.setBackground(Color.WHITE);
                    c.setFont(new Font("Segoe UI", Font.PLAIN, 11));
                    c.setForeground(new Color(50, 50, 50));
                }
                return c;
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(200, 200, 200)));
        scroll.getViewport().setBackground(Color.WHITE);
        panel.add(scroll);

        return panel;
    }

    private static JPanel buildRecurringSection(ReportData data) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(Color.WHITE);

        // Mini header
        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        titlePanel.setBackground(new Color(245, 245, 245));
        JLabel lbl = new JLabel("RESUMEN DE CARGOS RECURRENTES (CNR)");
        lbl.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lbl.setForeground(new Color(100, 100, 100));
        titlePanel.add(lbl);
        panel.add(titlePanel);

        // Items
        for (RecurringCharge rc : data.recurringCharges) {
            JPanel row = new JPanel(new BorderLayout());
            row.setBackground(Color.WHITE);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
            row.setBorder(BorderFactory.createEmptyBorder(2, 16, 2, 8));

            JLabel name = new JLabel("• " + rc.concept);
            name.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            row.add(name, BorderLayout.WEST);

            JLabel amount = new JLabel(FMT.format(rc.amount));
            amount.setFont(new Font("Segoe UI", Font.BOLD, 11));
            row.add(amount, BorderLayout.EAST);

            panel.add(row);
        }

        return panel;
    }

    private static void addGroupToRows(List<DetailRow> rows, String groupTitle,
            List<Transaction> transactions) {
        if (transactions.isEmpty())
            return;

        // Header row
        rows.add(new DetailRow(groupTitle, true));

        // Transaction rows
        for (Transaction t : transactions) {
            rows.add(new DetailRow(
                    t.getReference(),
                    t.getDate() != null ? t.getDate().toString() : "",
                    t.getDescription(),
                    FMT.format(t.getAbsAmount()),
                    false));
        }

        // Empty separator row
        rows.add(new DetailRow("", false));
    }

    // ────────── Helper Types ──────────

    static class DetailRow {
        String ref;
        String date;
        String description;
        String amount;
        boolean isHeader;

        // Header row constructor
        DetailRow(String description, boolean isHeader) {
            this.ref = "";
            this.date = "";
            this.description = description;
            this.amount = "";
            this.isHeader = isHeader;
        }

        // Data row constructor
        DetailRow(String ref, String date, String description, String amount, boolean isHeader) {
            this.ref = ref;
            this.date = date;
            this.description = description;
            this.amount = amount;
            this.isHeader = isHeader;
        }
    }

    static class DetailTableModel extends AbstractTableModel {
        private final List<DetailRow> rows;
        private final String[] columns;

        DetailTableModel(List<DetailRow> rows, String[] columns) {
            this.rows = rows;
            this.columns = columns;
        }

        @Override
        public int getRowCount() {
            return rows.size();
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
        public boolean isCellEditable(int row, int col) {
            return false;
        }

        @Override
        public Object getValueAt(int row, int col) {
            DetailRow r = rows.get(row);
            if (r.isHeader) {
                return col == 0 ? r.description : "";
            }
            return switch (col) {
                case 0 -> r.ref;
                case 1 -> r.date;
                case 2 -> r.description;
                case 3 -> r.amount;
                default -> "";
            };
        }
    }

    // ────────── Formatting ──────────

    private static String formatSigned(double value) {
        if (value < -0.001) {
            return "(" + FMT.format(Math.abs(value)) + ")";
        }
        return FMT.format(value);
    }
}
