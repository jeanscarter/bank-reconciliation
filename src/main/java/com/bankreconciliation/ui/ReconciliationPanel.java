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

    private TransactionTableModel bookModel;
    private TransactionTableModel bankModel;
    private List<NearMatch> pendingNearMatches;

    public ReconciliationPanel(List<Transaction> bookTransactions,
            List<Transaction> bankTransactions,
            BalanceSummaryPanel summaryPanel) {
        this.bookTransactions = bookTransactions;
        this.bankTransactions = bankTransactions;
        this.summaryPanel = summaryPanel;

        setOpaque(false);
        setLayout(new MigLayout("insets 0, gap 16, fillx, filly", "[grow, 50%][grow, 50%]", "[][grow]"));

        // ── Auto-reconciliation header ──
        JPanel topBar = buildTopBar();
        add(topBar, "span 2, growx, wrap");

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

        JButton reviewBtn = new JButton("🔍 Revisar Diferencias");
        reviewBtn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        reviewBtn.setForeground(new Color(255, 193, 7));
        reviewBtn.setContentAreaFilled(false);
        reviewBtn.setBorderPainted(false);
        reviewBtn.setFocusPainted(false);
        reviewBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        reviewBtn.addActionListener(e -> openNearMatchReview());
        bar.add(reviewBtn);

        return bar;
    }

    // ======================== Auto-Reconciliation ========================

    private void runAutoReconciliation() {
        ReconciliationEngine.Result result = ReconciliationEngine.autoReconcile(
                bookTransactions, bankTransactions);

        System.out.println(result.summary());
        refreshAll();

        // Find near-matches and show review overlay if any exist
        pendingNearMatches = ReconciliationEngine.findNearMatches(
                bookTransactions, bankTransactions);

        if (!pendingNearMatches.isEmpty()) {
            // Show overlay on next EDT tick (after tables are painted)
            SwingUtilities.invokeLater(this::openNearMatchReview);
        }
    }

    private void openNearMatchReview() {
        if (pendingNearMatches == null || pendingNearMatches.isEmpty()) {
            Toast.show("No hay coincidencias aproximadas pendientes", Toast.Type.INFO);
            return;
        }
        NearMatchReviewOverlay overlay = new NearMatchReviewOverlay(
                pendingNearMatches, this::refreshAll);
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
}
