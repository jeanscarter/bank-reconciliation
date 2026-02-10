package com.bankreconciliation.ui;

import com.bankreconciliation.model.Transaction;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

public class BalanceSummaryPanel extends JPanel {

    private static final NumberFormat FMT = NumberFormat.getCurrencyInstance(Locale.US);

    private final List<Transaction> bookTransactions;
    private final List<Transaction> bankTransactions;

    private JLabel bankBalanceValue;
    private JLabel transitDepositsValue;
    private JLabel transitChecksValue;
    private JLabel adjustmentsValue;
    private JLabel reconciledBalanceValue;
    private JLabel reconciledCountValue;

    public BalanceSummaryPanel(List<Transaction> bookTransactions, List<Transaction> bankTransactions) {
        this.bookTransactions = bookTransactions;
        this.bankTransactions = bankTransactions;

        setOpaque(false);
        setLayout(new MigLayout("insets 0, gap 16, fillx", "[grow][grow][grow][grow][grow][grow]", ""));

        bankBalanceValue = new JLabel();
        transitDepositsValue = new JLabel();
        transitChecksValue = new JLabel();
        adjustmentsValue = new JLabel();
        reconciledBalanceValue = new JLabel();
        reconciledCountValue = new JLabel();

        add(createCard("Saldo Banco", bankBalanceValue, "🏦", new Color(33, 150, 243)), "grow");
        add(createCard("Dep. en Tránsito", transitDepositsValue, "📥", new Color(76, 175, 80)), "grow");
        add(createCard("Cheques en Tránsito", transitChecksValue, "📤", new Color(244, 67, 54)), "grow");
        add(createCard("Ajustes", adjustmentsValue, "⚙", new Color(156, 39, 176)), "grow");
        add(createCard("Saldo Conciliado", reconciledBalanceValue, "✓", new Color(0, 150, 136)), "grow");
        add(createCard("Conciliadas", reconciledCountValue, "📊", new Color(255, 152, 0)), "grow");

        recalculate();
    }

    private JPanel createCard(String title, JLabel valueLabel, String icon, Color accentColor) {
        RoundedPanel card = new RoundedPanel(20) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Accent top bar
                g2.setColor(accentColor);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), 4, 4, 4));
                g2.dispose();
            }
        };
        card.setBackground(new Color(40, 44, 52));
        card.setLayout(new MigLayout("insets 16 16 12 16, wrap, fillx", "[grow]", ""));

        JPanel headerRow = new JPanel(new MigLayout("insets 0, fillx", "[]push[]", ""));
        headerRow.setOpaque(false);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        titleLabel.setForeground(new Color(160, 160, 170));
        headerRow.add(titleLabel);

        JLabel iconLabel = new JLabel(icon);
        iconLabel.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 18));
        headerRow.add(iconLabel);

        card.add(headerRow, "growx");

        valueLabel.setFont(new Font("Segoe UI", Font.BOLD, 22));
        valueLabel.setForeground(Color.WHITE);
        card.add(valueLabel, "gaptop 4");

        return card;
    }

    public void recalculate() {
        double bankBalance = 0;
        for (Transaction t : bankTransactions) {
            bankBalance += t.getDeposit() - t.getWithdrawal();
        }

        double transitDeposits = 0;
        double transitChecks = 0;
        double adjustments = 0;
        int reconciledCount = 0;

        for (Transaction t : bookTransactions) {
            if (t.isPending()) {
                if (t.getDeposit() > 0)
                    transitDeposits += t.getDeposit();
                if (t.getWithdrawal() > 0)
                    transitChecks += t.getWithdrawal();
            } else if (t.getStatus() == Transaction.Status.OPC) {
                reconciledCount++;
            } else {
                adjustments += t.getNetAmount();
            }
        }
        for (Transaction t : bankTransactions) {
            if (t.getStatus() == Transaction.Status.OPC) {
                reconciledCount++;
            }
            if (!t.isPending() && t.getStatus() != Transaction.Status.OPC) {
                adjustments += t.getNetAmount();
            }
        }

        double reconciledBalance = bankBalance + transitDeposits - transitChecks + adjustments;

        bankBalanceValue.setText(FMT.format(bankBalance));
        transitDepositsValue.setText(FMT.format(transitDeposits));
        transitDepositsValue.setForeground(new Color(76, 175, 80));
        transitChecksValue.setText(FMT.format(transitChecks));
        transitChecksValue.setForeground(new Color(244, 67, 54));
        adjustmentsValue.setText(FMT.format(adjustments));
        reconciledBalanceValue.setText(FMT.format(reconciledBalance));
        reconciledCountValue.setText(String.valueOf(reconciledCount));
    }
}
