package com.bankreconciliation;

import com.bankreconciliation.model.Transaction;
import com.bankreconciliation.ui.BalanceSummaryPanel;
import com.bankreconciliation.ui.FileUploadPanel;
import com.bankreconciliation.ui.ModalManager;
import com.bankreconciliation.ui.ReconciliationPanel;
import com.bankreconciliation.ui.Toast;
import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class MainFrame extends JFrame {

    private static final Color ROOT_BG = new Color(30, 33, 40);
    private JPanel rootPanel;
    private CardLayout cardLayout;

    public MainFrame() {
        setTitle("Conciliación Bancaria — Bank Reconciliation");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1440, 860);
        setMinimumSize(new Dimension(1100, 700));
        setLocationRelativeTo(null);

        // Init overlay systems
        ModalManager.init(this);
        Toast.setParentFrame(this);

        // Root with CardLayout for phase switching
        cardLayout = new CardLayout();
        rootPanel = new JPanel(cardLayout);
        rootPanel.setBackground(ROOT_BG);

        // Phase 1: Upload view
        final FileUploadPanel[] uploadRef = new FileUploadPanel[1];
        FileUploadPanel uploadPanel = new FileUploadPanel((bookTxns, bankTxns) -> {
            showReconciliation(bookTxns, bankTxns, uploadRef[0].getSaldoInicial());
        });
        uploadRef[0] = uploadPanel;
        rootPanel.add(uploadPanel, "UPLOAD");

        // Start on upload phase
        cardLayout.show(rootPanel, "UPLOAD");
        setContentPane(rootPanel);

        // Apply FlatLaf title bar
        getRootPane().putClientProperty(FlatClientProperties.TITLE_BAR_BACKGROUND, ROOT_BG);
        getRootPane().putClientProperty(FlatClientProperties.TITLE_BAR_FOREGROUND, Color.WHITE);
    }

    public void showReconciliation(List<Transaction> bookTransactions,
            List<Transaction> bankTransactions,
            double saldoInicial) {
        // Build reconciliation view
        JPanel reconcView = new JPanel(new MigLayout("insets 24, fill, wrap, gap 0 20",
                "[grow]", "[][][][grow][]"));
        reconcView.setBackground(ROOT_BG);

        // Title bar
        JPanel titleBar = new JPanel(new MigLayout("insets 0, fillx", "[]push[][]", ""));
        titleBar.setOpaque(false);

        JLabel appTitle = new JLabel("Conciliación Bancaria");
        appTitle.setFont(new Font("Segoe UI", Font.BOLD, 26));
        appTitle.setForeground(Color.WHITE);
        titleBar.add(appTitle);

        JLabel subtitle = new JLabel("Sistema de Conciliación Automática");
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        subtitle.setForeground(new Color(130, 140, 160));
        titleBar.add(subtitle);

        // Back button
        JButton backBtn = new JButton("← Cargar nuevos archivos");
        backBtn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        backBtn.setForeground(new Color(100, 160, 255));
        backBtn.setContentAreaFilled(false);
        backBtn.setBorderPainted(false);
        backBtn.setFocusPainted(false);
        backBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        backBtn.addActionListener(e -> {
            // Remove reconciliation view and go back to upload
            rootPanel.remove(reconcView);
            cardLayout.show(rootPanel, "UPLOAD");
        });
        titleBar.add(backBtn);

        reconcView.add(titleBar, "growx");

        // Balance summary cards
        BalanceSummaryPanel summaryPanel = new BalanceSummaryPanel(bookTransactions, bankTransactions, saldoInicial);
        reconcView.add(summaryPanel, "growx, h 120!");

        // Saldo Inicial banner
        JPanel saldoBanner = createSaldoBanner(saldoInicial);
        reconcView.add(saldoBanner, "growx, h 36!");

        // Main reconciliation area
        ReconciliationPanel reconcPanel = new ReconciliationPanel(bookTransactions, bankTransactions, summaryPanel);
        reconcView.add(reconcPanel, "grow");

        // Footer
        JPanel footer = new JPanel(new MigLayout("insets 0, fillx", "push[]push", ""));
        footer.setOpaque(false);
        JLabel footerLabel = new JLabel("Doble clic en una transacción pendiente para buscar coincidencias");
        footerLabel.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        footerLabel.setForeground(new Color(100, 110, 130));
        footer.add(footerLabel);
        reconcView.add(footer, "growx, h 28!");

        rootPanel.add(reconcView, "RECONCILIATION");
        cardLayout.show(rootPanel, "RECONCILIATION");
    }

    private JPanel createSaldoBanner(double saldoInicial) {
        JPanel banner = new JPanel(new MigLayout("insets 4 16 4 16, fillx", "push[]push", ""));
        banner.setOpaque(false);

        String formatted = String.format("%,.2f", saldoInicial);
        JLabel label = new JLabel("Saldo Inicial del Estado de Cuenta:  " + formatted + " Bs");
        label.setFont(new Font("Segoe UI", Font.BOLD, 13));
        label.setForeground(new Color(52, 168, 83));
        banner.add(label);

        return banner;
    }
}
