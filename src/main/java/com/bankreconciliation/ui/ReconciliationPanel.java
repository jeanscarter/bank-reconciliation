package com.bankreconciliation.ui;

import com.bankreconciliation.ReconciliationEngine;
import com.bankreconciliation.ReconciliationEngine.NearMatch;
import com.bankreconciliation.model.Transaction;
import com.bankreconciliation.ui.table.BadgeRenderer;
import com.bankreconciliation.ui.table.CurrencyRenderer;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Comparator;
import java.util.List;

public class ReconciliationPanel extends JPanel {

    private final List<Transaction> bookTransactions;
    private final List<Transaction> bankTransactions;
    private final BalanceSummaryPanel summaryPanel;
    private final double saldoInicial;

    private TransactionTableModel bookModel;
    private TransactionTableModel bankModel;
    private List<NearMatch> pendingNearMatches;

    private RecurringChargesPanel recurringChargesPanel;

    public ReconciliationPanel(List<Transaction> bookTransactions,
            List<Transaction> bankTransactions,
            BalanceSummaryPanel summaryPanel,
            double saldoInicial) {
        this.bookTransactions = bookTransactions;
        this.bankTransactions = bankTransactions;
        this.summaryPanel = summaryPanel;
        this.saldoInicial = saldoInicial;

        setOpaque(false);
        setLayout(new MigLayout("insets 0, gap 16, fillx, filly", "[grow, 50%][grow, 50%]", "[][][grow]"));

        // ── Auto-reconciliation header ──
        JPanel topBar = buildTopBar();
        add(topBar, "span 2, growx, wrap");

        // ── Recurring Charges Widget ──
        recurringChargesPanel = new RecurringChargesPanel();
        add(recurringChargesPanel, "span 2, growx, wrap");

        JPanel bookPanel = createTablePanel("📘  Libro Contable", bookTransactions, Transaction.Source.BOOK,
                new String[] { "Fecha", "Ref", "Descripción", "Debe", "Haber", "Estado" });
        JPanel bankPanel = createTablePanel("🏦  Estado de Cuenta Bancario", bankTransactions, Transaction.Source.BANK,
                new String[] { "Fecha", "Ref", "Descripción", "Depósito", "Retiro", "Estado" });

        add(bookPanel, "grow");
        add(bankPanel, "grow");

        // Run auto-reconciliation immediately
        runAutoReconciliation();
    }

    // ======================== Top Bar ========================

    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new MigLayout("insets 0, fillx", "push[][]", ""));
        bar.setOpaque(false);

        JLabel autoLabel = new JLabel("Sistema de Conciliación Automática");
        autoLabel.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        autoLabel.setForeground(new Color(160, 160, 170));
        bar.add(autoLabel, "gapright 12");

        // Report Button
        JButton reportBtn = new JButton("📄 Reporte");
        reportBtn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        reportBtn.setForeground(Color.WHITE);
        reportBtn.setContentAreaFilled(false);
        reportBtn.setBorderPainted(false);
        reportBtn.setFocusPainted(false);
        reportBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        reportBtn.addActionListener(e -> {
            ReportOverlay overlay = new ReportOverlay(bookTransactions, bankTransactions, saldoInicial);
            ModalManager.show(overlay);
        });
        bar.add(reportBtn);

        JButton reviewBtn = new JButton("🔍 Revisar Diferencias");
        reviewBtn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        reviewBtn.setForeground(new Color(255, 193, 7));
        reviewBtn.setContentAreaFilled(false);
        reviewBtn.setBorderPainted(false);
        reviewBtn.setFocusPainted(false);
        reviewBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        reviewBtn.addActionListener(e -> openNearMatchReview());
        bar.add(reviewBtn);

        JButton largeDiffBtn = new JButton("⚠️ Diferencias Mayores");
        largeDiffBtn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        largeDiffBtn.setForeground(new Color(255, 100, 100)); // Red-ish
        largeDiffBtn.setContentAreaFilled(false);
        largeDiffBtn.setBorderPainted(false);
        largeDiffBtn.setFocusPainted(false);
        largeDiffBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        largeDiffBtn.addActionListener(e -> openLargeDiffReview());
        bar.add(largeDiffBtn);

        return bar;
    }

    private void openLargeDiffReview() {
        // Find large differences
        List<NearMatch> largeDiffs = ReconciliationEngine.findLargeDifferences(bookTransactions, bankTransactions);

        if (largeDiffs.isEmpty()) {
            Toast.show("No se encontraron diferencias mayores a 1.00", Toast.Type.INFO);
            return;
        }

        LargeDifferenceReviewOverlay overlay = new LargeDifferenceReviewOverlay(largeDiffs);
        ModalManager.show(overlay);
    }

    // ======================== Auto-Reconciliation ========================

    private void runAutoReconciliation() {
        // Step 1: Perform exact matching (Ref+Amount, Amount-only)
        // This does NOT assign unmatched statuses (DNA/RNE/etc) yet.
        ReconciliationEngine.Result result = ReconciliationEngine.performExactMatch(
                bookTransactions, bankTransactions);

        System.out.println("Fase 1 (Exacta): " + result.totalMatched() + " conciliadas.");
        refreshAll();

        // Step 2: Find near-matches (Same Ref + diff amount, or similar amount)
        pendingNearMatches = ReconciliationEngine.findNearMatches(
                bookTransactions, bankTransactions);

        if (!pendingNearMatches.isEmpty()) {
            // Show overlay. On close, proceed to Step 3.
            SwingUtilities.invokeLater(() -> {
                Toast.show("Se encontraron " + pendingNearMatches.size() + " posibles coincidencias.", Toast.Type.INFO);
                openNearMatchReview();
            });
        } else {
            // No near matches -> proceed immediately to Step 3
            finalizeReconciliation();
        }
    }

    private void finalizeReconciliation() {
        // Step 3: Assign statuses to remaining PENDING transactions
        ReconciliationEngine.Result result = ReconciliationEngine.assignUnmatchedStatuses(
                bookTransactions, bankTransactions);

        System.out.println("Fase 3 (Final): " + result.unmatched() + " asignadas como no conciliadas.");
        refreshAll();
    }

    private void openNearMatchReview() {
        if (pendingNearMatches == null || pendingNearMatches.isEmpty()) {
            Toast.show("No hay coincidencias aproximadas pendientes", Toast.Type.INFO);
            return;
        }

        // When overlay closes (after user approves some), finalizes the process
        NearMatchReviewOverlay overlay = new NearMatchReviewOverlay(
                pendingNearMatches, () -> {
                    finalizeReconciliation();
                });
        ModalManager.show(overlay);
    }

    // ======================== Table Panel ========================

    private JPanel createTablePanel(String title, List<Transaction> transactions, Transaction.Source source,
            String[] columnNames) {
        RoundedPanel panel = new RoundedPanel(20);
        panel.setBackground(new Color(40, 44, 52));
        panel.setLayout(new MigLayout("insets 20, fill, wrap", "[grow]", "[][grow]"));

        // Header row
        JPanel headerRow = new JPanel(new MigLayout("insets 0, fillx", "[]push[]", ""));
        headerRow.setOpaque(false);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 16));
        titleLabel.setForeground(Color.WHITE);
        headerRow.add(titleLabel);

        long pendingCount = transactions.stream().filter(Transaction::isPending).count();
        JLabel countLabel = new JLabel(pendingCount + " pendientes");
        countLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        countLabel.setForeground(new Color(160, 160, 170));
        headerRow.add(countLabel);

        panel.add(headerRow, "growx");

        // Table
        TransactionTableModel model = new TransactionTableModel(transactions, columnNames);
        if (source == Transaction.Source.BOOK)
            bookModel = model;
        else
            bankModel = model;

        JTable table = new JTable(model);
        table.setRowHeight(40);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
        table.getTableHeader().setReorderingAllowed(false);

        table.putClientProperty("FlatLaf.style",
                "showHorizontalLines: false; showVerticalLines: false; " +
                        "cellFocusColor: #00000000; selectionBackground: #2d3748; selectionForeground: #ffffff");

        // Column renderers
        table.getColumnModel().getColumn(3).setCellRenderer(new CurrencyRenderer(true));
        table.getColumnModel().getColumn(4).setCellRenderer(new CurrencyRenderer(true));
        table.getColumnModel().getColumn(5).setCellRenderer(new BadgeRenderer());

        // Column widths
        table.getColumnModel().getColumn(0).setPreferredWidth(90); // Fecha
        table.getColumnModel().getColumn(1).setPreferredWidth(80); // Ref
        table.getColumnModel().getColumn(2).setPreferredWidth(200); // Descripción
        table.getColumnModel().getColumn(3).setPreferredWidth(100); // Depósito/Debe
        table.getColumnModel().getColumn(4).setPreferredWidth(100); // Retiro/Haber
        table.getColumnModel().getColumn(5).setPreferredWidth(80); // Estado

        // ── Row Sorter (click column headers to sort) ──
        TableRowSorter<TransactionTableModel> sorter = new TableRowSorter<>(model);

        // Custom comparator for Status column (col 5) — sort by enum ordinal
        sorter.setComparator(5, Comparator.comparingInt((Transaction.Status s) -> s.ordinal()));

        table.setRowSorter(sorter);

        // Double-click listener — use view row to get model row
        List<Transaction> oppositeList = (source == Transaction.Source.BOOK) ? bankTransactions : bookTransactions;
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int viewRow = table.rowAtPoint(e.getPoint());
                    if (viewRow >= 0) {
                        int modelRow = table.convertRowIndexToModel(viewRow);
                        Transaction selected = transactions.get(modelRow);
                        if (!selected.isPending()) {
                            Toast.show("Esta transacción ya fue procesada", Toast.Type.INFO);
                            return;
                        }
                        openMatcher(selected, oppositeList);
                    }
                }
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(new Color(40, 44, 52));
        panel.add(scroll, "grow");

        return panel;
    }

    private void openMatcher(Transaction selected, List<Transaction> oppositeList) {
        MatcherOverlay overlay = new MatcherOverlay(
                selected,
                oppositeList,
                matched -> {
                    // Conciliate
                    selected.setStatus(Transaction.Status.OPC);
                    selected.setMatchedId(matched.getId());
                    matched.setStatus(Transaction.Status.OPC);
                    matched.setMatchedId(selected.getId());

                    ModalManager.dismiss(() -> {
                        Toast.show("Partida Conciliada ✓", Toast.Type.SUCCESS);
                        refreshAll();
                    });
                },
                this::refreshAll);
        ModalManager.show(overlay);
    }

    private void refreshAll() {
        if (bookModel != null)
            bookModel.fireTableDataChanged();
        if (bankModel != null)
            bankModel.fireTableDataChanged();
        if (summaryPanel != null)
            summaryPanel.recalculate();
        if (recurringChargesPanel != null) {
            // Filter only CNR transactions from bank side
            List<Transaction> cnrList = bankTransactions.stream()
                    .filter(t -> t.getStatus() == Transaction.Status.CNR)
                    .toList();
            recurringChargesPanel.update(cnrList);
        }
    }

    // ======================== Table Model ========================

    public static class TransactionTableModel extends AbstractTableModel {

        private final String[] columns;
        private final List<Transaction> data;

        public TransactionTableModel(List<Transaction> data, String[] columns) {
            this.data = data;
            this.columns = columns;
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
        public boolean isCellEditable(int row, int col) {
            return false;
        }

        @Override
        public Class<?> getColumnClass(int col) {
            return switch (col) {
                case 3, 4 -> Double.class;
                case 5 -> Transaction.Status.class;
                default -> String.class;
            };
        }

        @Override
        public Object getValueAt(int row, int col) {
            Transaction t = data.get(row);
            return switch (col) {
                case 0 -> t.getDate().toString();
                case 1 -> t.getReference();
                case 2 -> t.getDescription();
                case 3 -> t.getDeposit();
                case 4 -> t.getWithdrawal();
                case 5 -> t.getStatus();
                default -> "";
            };
        }
    }

    // ======================== Recurring Charges Widget ========================

    private static class RecurringChargesPanel extends RoundedPanel {
        private final java.util.Map<String, JLabel> valueLabels = new java.util.HashMap<>();
        private static final java.text.DecimalFormat FMT = new java.text.DecimalFormat("#,##0.00");

        public RecurringChargesPanel() {
            super(15);
            setBackground(new Color(45, 50, 60));
            setLayout(new MigLayout("insets 10 20 10 20, fillx", "[]20[]20[]20[]20[]", "[]"));

            addWidget("COMISIONES");
            addWidget("MANTENIMIENTO");
            addWidget("ITF");
            addWidget("IVA");
            addWidget("CHEQUERA");
        }

        private void addWidget(String title) {
            JPanel p = new JPanel(new MigLayout("insets 0", "[]10[]", "[]"));
            p.setOpaque(false);

            JLabel titleLbl = new JLabel(title);
            titleLbl.setFont(new Font("Segoe UI", Font.BOLD, 12));
            titleLbl.setForeground(new Color(160, 160, 170));

            JLabel valueLbl = new JLabel("0.00");
            valueLbl.setFont(new Font("Segoe UI", Font.BOLD, 14));
            valueLbl.setForeground(new Color(255, 100, 100)); // Red-ish for charges

            p.add(titleLbl);
            p.add(valueLbl);

            add(p);
            valueLabels.put(title, valueLbl);
        }

        public void update(List<Transaction> transactions) {
            java.util.Map<String, Double> totals = com.bankreconciliation.util.RecurringChargesCalculator
                    .calculate(transactions);

            totals.forEach((key, val) -> {
                JLabel lbl = valueLabels.get(key);
                if (lbl != null) {
                    lbl.setText(FMT.format(val));
                    // Highlight if > 0
                    if (val > 0) {
                        lbl.setForeground(new Color(255, 100, 100));
                    } else {
                        lbl.setForeground(new Color(100, 100, 110));
                    }
                }
            });
        }
    }
}
