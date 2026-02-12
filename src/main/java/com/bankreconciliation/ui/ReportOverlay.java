package com.bankreconciliation.ui;

import com.bankreconciliation.model.Transaction;
import com.bankreconciliation.report.ReconciliationReportGenerator;
import com.bankreconciliation.report.ReconciliationReportGenerator.ReportData;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Overlay modal que muestra el Reporte de Conciliación Bancaria
 * usando el sistema ModalManager existente.
 */
public class ReportOverlay extends JPanel {

    public ReportOverlay(List<Transaction> bookTransactions,
            List<Transaction> bankTransactions,
            double saldoInicial) {
        setOpaque(false);
        setLayout(new GridBagLayout()); // Centering the card

        // ── Card Container ──
        RoundedPanel card = new RoundedPanel(20);
        card.setBackground(new Color(45, 50, 58));
        card.setLayout(new MigLayout("insets 24, fill, wrap", "[grow]", "[][grow][]"));
        card.setPreferredSize(new Dimension(1100, 650));

        // ── Header ──
        JPanel header = new JPanel(new MigLayout("insets 0, fillx", "[]push[]", ""));
        header.setOpaque(false);

        JLabel titleLabel = new JLabel("📄  Reporte de Conciliación Bancaria");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 20));
        titleLabel.setForeground(Color.WHITE);
        header.add(titleLabel);

        JButton closeBtn = new JButton("✕");
        closeBtn.setFont(new Font("Segoe UI", Font.BOLD, 16));
        closeBtn.setForeground(new Color(160, 160, 170));
        closeBtn.setContentAreaFilled(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setFocusPainted(false);
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.addActionListener(e -> ModalManager.dismiss(null));
        header.add(closeBtn);

        card.add(header, "growx");

        // ── Report Content ──
        ReportData reportData = ReconciliationReportGenerator.calculate(
                bookTransactions, bankTransactions, saldoInicial);
        JPanel reportPanel = ReconciliationReportGenerator.buildReportPanel(reportData);

        JScrollPane scrollPane = new JScrollPane(reportPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(Color.WHITE);
        card.add(scrollPane, "grow");

        // ── Footer ──
        JPanel footer = new JPanel(new MigLayout("insets 8 0 0 0, fillx", "push[]", ""));
        footer.setOpaque(false);

        JButton exportBtn = new JButton("💾 Exportar Excel");
        exportBtn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        exportBtn.setForeground(Color.WHITE);
        exportBtn.setBackground(new Color(40, 167, 69)); // Green
        exportBtn.setFocusPainted(false);
        exportBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        exportBtn.setPreferredSize(new Dimension(150, 36));
        exportBtn.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("Guardar Reporte de Conciliación");
            fileChooser.setFileFilter(
                    new javax.swing.filechooser.FileNameExtensionFilter("Archivos Excel (*.xlsx)", "xlsx"));
            fileChooser.setSelectedFile(new java.io.File("Conciliacion_Bancaria.xlsx"));

            if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                java.io.File file = fileChooser.getSelectedFile();
                if (!file.getName().toLowerCase().endsWith(".xlsx")) {
                    file = new java.io.File(file.getParentFile(), file.getName() + ".xlsx");
                }

                try {
                    com.bankreconciliation.report.ReconciliationExcelExporter.export(reportData,
                            file.getAbsolutePath());
                    Toast.show("Reporte exportado exitosamente", Toast.Type.SUCCESS);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    Toast.show("Error al exportar reporte: " + ex.getMessage(), Toast.Type.ERROR);
                }
            }
        });
        footer.add(exportBtn, "gapright 12");

        JButton closeButton = new JButton("Cerrar");
        closeButton.setFont(new Font("Segoe UI", Font.BOLD, 13));
        closeButton.setForeground(Color.WHITE);
        closeButton.setBackground(new Color(0, 100, 80));
        closeButton.setFocusPainted(false);
        closeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeButton.setPreferredSize(new Dimension(120, 36));
        closeButton.addActionListener(e -> ModalManager.dismiss(null));
        footer.add(closeButton);

        card.add(footer, "growx");

        // ── Add card centered ──
        add(card);
    }

    @Override
    protected void paintComponent(Graphics g) {
        // Semi-transparent background
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(new Color(0, 0, 0, 180));
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.dispose();
        super.paintComponent(g);
    }
}
