package com.bankreconciliation;

import com.bankreconciliation.model.Transaction;
import com.bankreconciliation.ui.BalanceSummaryPanel;
import com.bankreconciliation.ui.ModalManager;
import com.bankreconciliation.ui.ReconciliationPanel;
import com.bankreconciliation.ui.Toast;
import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class MainFrame extends JFrame {

    public MainFrame(List<Transaction> bookTransactions, List<Transaction> bankTransactions) {
        setTitle("Conciliación Bancaria — Bank Reconciliation");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1440, 860);
        setMinimumSize(new Dimension(1100, 700));
        setLocationRelativeTo(null);

        // Init overlay systems
        ModalManager.init(this);
        Toast.setParentFrame(this);

        // Root panel
        JPanel root = new JPanel(new MigLayout("insets 24, fill, wrap, gap 0 20",
                "[grow]", "[][grow]"));
        root.setBackground(new Color(30, 33, 40));

        // Title bar
        JPanel titleBar = new JPanel(new MigLayout("insets 0, fillx", "[]push[]", ""));
        titleBar.setOpaque(false);

        JLabel appTitle = new JLabel("Conciliación Bancaria");
        appTitle.setFont(new Font("Segoe UI", Font.BOLD, 26));
        appTitle.setForeground(Color.WHITE);
        titleBar.add(appTitle);

        JLabel subtitle = new JLabel("Sistema de Conciliación Automática");
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        subtitle.setForeground(new Color(130, 140, 160));
        titleBar.add(subtitle);

        root.add(titleBar, "growx");

        // Balance summary cards
        BalanceSummaryPanel summaryPanel = new BalanceSummaryPanel(bookTransactions, bankTransactions);
        root.add(summaryPanel, "growx, h 120!");

        // Main reconciliation area
        ReconciliationPanel reconcPanel = new ReconciliationPanel(bookTransactions, bankTransactions, summaryPanel);
        root.add(reconcPanel, "grow");

        // Footer
        JPanel footer = new JPanel(new MigLayout("insets 0, fillx", "push[]push", ""));
        footer.setOpaque(false);
        JLabel footerLabel = new JLabel("Doble clic en una transacción pendiente para buscar coincidencias");
        footerLabel.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        footerLabel.setForeground(new Color(100, 110, 130));
        footer.add(footerLabel);
        root.add(footer, "growx, h 28!");

        setContentPane(root);

        // Apply FlatLaf properties globally
        getRootPane().putClientProperty(FlatClientProperties.TITLE_BAR_BACKGROUND, new Color(30, 33, 40));
        getRootPane().putClientProperty(FlatClientProperties.TITLE_BAR_FOREGROUND, Color.WHITE);
    }
}
