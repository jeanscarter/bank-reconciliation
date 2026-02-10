package com.bankreconciliation.ui;

import com.bankreconciliation.model.Transaction;
import com.bankreconciliation.parser.ColumnMapper;
import com.bankreconciliation.parser.FileParser;
import com.bankreconciliation.parser.ParserFactory;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Full-screen upload view with two sections for Libro Contable and Estado de
 * Cuenta.
 * Files can be uploaded via buttons or drag-and-drop.
 */
public class FileUploadPanel extends JPanel {

    private static final Color PANEL_BG = new Color(40, 44, 52);
    private static final Color ROOT_BG = new Color(30, 33, 40);
    private static final Color ACCENT_BLUE = new Color(66, 133, 244);
    private static final Color ACCENT_GREEN = new Color(52, 168, 83);
    private static final Color BORDER_DASHED = new Color(80, 90, 110);
    private static final Color TEXT_MUTED = new Color(130, 140, 160);
    private static final Color TEXT_WHITE = Color.WHITE;
    private static final NumberFormat CURRENCY_FMT = NumberFormat.getNumberInstance(new Locale("es", "VE"));

    static {
        CURRENCY_FMT.setMinimumFractionDigits(2);
        CURRENCY_FMT.setMaximumFractionDigits(2);
    }

    private List<Transaction> bookTransactions = new ArrayList<>();
    private List<Transaction> bankTransactions = new ArrayList<>();
    private double saldoInicial = 0.0;

    private JLabel bookStatusLabel;
    private JLabel bankStatusLabel;
    private JTextField saldoInicialField;
    private JTable bookPreviewTable;
    private JTable bankPreviewTable;
    private PreviewTableModel bookPreviewModel;
    private PreviewTableModel bankPreviewModel;
    private JButton continueButton;

    // Drop zone panels for visual feedback
    private DropZonePanel bookDropZone;
    private DropZonePanel bankDropZone;

    private final BiConsumer<List<Transaction>, List<Transaction>> onContinue;
    private final Runnable onSaldoInicialChanged;

    /**
     * @param onContinue callback receiving (bookTxns, bankTxns) when user clicks
     *                   Continue
     */
    public FileUploadPanel(BiConsumer<List<Transaction>, List<Transaction>> onContinue) {
        this.onContinue = onContinue;
        this.onSaldoInicialChanged = null;
        buildUI();
    }

    public double getSaldoInicial() {
        try {
            String text = saldoInicialField.getText().trim().replaceAll("[^\\d.,-]", "");
            return ColumnMapper.parseAmount(text);
        } catch (Exception e) {
            return saldoInicial;
        }
    }

    private void buildUI() {
        setBackground(ROOT_BG);
        setLayout(new MigLayout("insets 32, fill, wrap", "[grow]", "[][grow][]"));

        // ── Title Section ──
        JPanel headerPanel = new JPanel(new MigLayout("insets 0, fillx, wrap", "[grow]", ""));
        headerPanel.setOpaque(false);

        JLabel title = new JLabel("Conciliación Bancaria");
        title.setFont(new Font("Segoe UI", Font.BOLD, 28));
        title.setForeground(TEXT_WHITE);
        headerPanel.add(title);

        JLabel subtitle = new JLabel(
                "Carga los archivos del Libro Contable y Estado de Cuenta para iniciar la conciliación");
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        subtitle.setForeground(TEXT_MUTED);
        headerPanel.add(subtitle, "gaptop 4");

        add(headerPanel, "growx");

        // ── Two-Column Upload Area ──
        JPanel uploadArea = new JPanel(new MigLayout("insets 0, gap 20, fillx, filly",
                "[grow, 50%][grow, 50%]", "[grow]"));
        uploadArea.setOpaque(false);

        uploadArea.add(createUploadSection("📘  Libro Contable", Transaction.Source.BOOK, ACCENT_BLUE), "grow");
        uploadArea.add(createUploadSection("🏦  Estado de Cuenta Bancario", Transaction.Source.BANK, ACCENT_GREEN),
                "grow");

        add(uploadArea, "grow");

        // ── Bottom Bar with Continue Button ──
        JPanel bottomBar = new JPanel(new MigLayout("insets 12 0 0 0, fillx", "push[]", ""));
        bottomBar.setOpaque(false);

        continueButton = createAccentButton("Continuar a Conciliación  →", new Color(46, 125, 50));
        continueButton.setEnabled(false);
        continueButton.setPreferredSize(new Dimension(280, 48));
        continueButton.addActionListener(e -> {
            if (!bookTransactions.isEmpty() && !bankTransactions.isEmpty()) {
                onContinue.accept(bookTransactions, bankTransactions);
            }
        });
        bottomBar.add(continueButton);

        add(bottomBar, "growx");
    }

    private JPanel createUploadSection(String title, Transaction.Source source, Color accent) {
        RoundedPanel section = new RoundedPanel(20);
        section.setBackground(PANEL_BG);
        section.setLayout(new MigLayout("insets 24, fill, wrap", "[grow]", "[][grow]"));

        // ── Header with title and optional Saldo Inicial ──
        JPanel headerRow = new JPanel(new MigLayout("insets 0, fillx", "[]push[]", ""));
        headerRow.setOpaque(false);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 17));
        titleLabel.setForeground(TEXT_WHITE);
        headerRow.add(titleLabel);

        if (source == Transaction.Source.BANK) {
            JPanel saldoPanel = new JPanel(new MigLayout("insets 0, gap 6", "[][]", ""));
            saldoPanel.setOpaque(false);

            JLabel saldoLabel = new JLabel("Saldo Inicial:");
            saldoLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
            saldoLabel.setForeground(ACCENT_GREEN);
            saldoPanel.add(saldoLabel);

            saldoInicialField = new JTextField("0.00");
            saldoInicialField.setFont(new Font("Segoe UI", Font.BOLD, 14));
            saldoInicialField.setForeground(TEXT_WHITE);
            saldoInicialField.setBackground(new Color(50, 55, 65));
            saldoInicialField.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(70, 80, 95), 1),
                    BorderFactory.createEmptyBorder(4, 8, 4, 8)));
            saldoInicialField.setPreferredSize(new Dimension(160, 32));
            saldoInicialField.setHorizontalAlignment(JTextField.RIGHT);
            saldoPanel.add(saldoInicialField);

            JLabel bsLabel = new JLabel("Bs");
            bsLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
            bsLabel.setForeground(TEXT_MUTED);
            saldoPanel.add(bsLabel);

            headerRow.add(saldoPanel);
        }

        section.add(headerRow, "growx");

        // ── Drop Zone + Preview Area ──
        JPanel contentArea = new JPanel(new CardLayout());
        contentArea.setOpaque(false);

        // Drop zone card
        DropZonePanel dropZone = new DropZonePanel(accent, source);
        if (source == Transaction.Source.BOOK)
            bookDropZone = dropZone;
        else
            bankDropZone = dropZone;
        contentArea.add(dropZone, "DROPZONE");

        // Preview card (shown after file is loaded)
        JPanel previewCard = new JPanel(new MigLayout("insets 0, fill, wrap", "[grow]", "[][grow]"));
        previewCard.setOpaque(false);

        JPanel previewHeader = new JPanel(new MigLayout("insets 0, fillx", "[]push[][]", ""));
        previewHeader.setOpaque(false);

        JLabel statusLabel = new JLabel("Sin archivo");
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        statusLabel.setForeground(TEXT_MUTED);
        if (source == Transaction.Source.BOOK)
            bookStatusLabel = statusLabel;
        else
            bankStatusLabel = statusLabel;
        previewHeader.add(statusLabel);

        JButton changeFileBtn = createSmallButton("Cambiar archivo", accent);
        changeFileBtn.addActionListener(e -> openFileChooser(source, accent));
        previewHeader.add(changeFileBtn);

        previewCard.add(previewHeader, "growx");

        // Preview table
        String[] previewCols = source == Transaction.Source.BOOK
                ? new String[] { "Fecha", "Referencia", "Descripción", "Debe", "Haber" }
                : new String[] { "Fecha", "Referencia", "Descripción", "Depósito", "Retiro" };

        PreviewTableModel model = new PreviewTableModel(previewCols);
        if (source == Transaction.Source.BOOK)
            bookPreviewModel = model;
        else
            bankPreviewModel = model;

        JTable previewTable = new JTable(model);
        previewTable.setRowHeight(34);
        previewTable.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        previewTable.setShowGrid(false);
        previewTable.setIntercellSpacing(new Dimension(0, 0));
        previewTable.setFillsViewportHeight(true);
        previewTable.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 11));
        previewTable.getTableHeader().setReorderingAllowed(false);
        previewTable.putClientProperty("FlatLaf.style",
                "showHorizontalLines: false; showVerticalLines: false; " +
                        "cellFocusColor: #00000000; selectionBackground: #2d3748; selectionForeground: #ffffff");

        // Currency renderer for amount columns
        DefaultTableCellRenderer rightRenderer = new DefaultTableCellRenderer();
        rightRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
        previewTable.getColumnModel().getColumn(3).setCellRenderer(rightRenderer);
        previewTable.getColumnModel().getColumn(4).setCellRenderer(rightRenderer);

        previewTable.getColumnModel().getColumn(0).setPreferredWidth(85);
        previewTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        previewTable.getColumnModel().getColumn(2).setPreferredWidth(200);
        previewTable.getColumnModel().getColumn(3).setPreferredWidth(100);
        previewTable.getColumnModel().getColumn(4).setPreferredWidth(100);

        if (source == Transaction.Source.BOOK)
            bookPreviewTable = previewTable;
        else
            bankPreviewTable = previewTable;

        JScrollPane scroll = new JScrollPane(previewTable);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(PANEL_BG);
        previewCard.add(scroll, "grow");

        contentArea.add(previewCard, "PREVIEW");

        section.add(contentArea, "grow");
        return section;
    }

    /**
     * Inner panel with dashed border for file drop zone.
     */
    private class DropZonePanel extends RoundedPanel {
        private final Color accent;
        private final Transaction.Source source;
        private boolean hovering = false;

        DropZonePanel(Color accent, Transaction.Source source) {
            super(16, false);
            this.accent = accent;
            this.source = source;
            setBackground(new Color(35, 39, 46));
            setLayout(new MigLayout("insets 40, fill, wrap, al center center", "[center]", "[center]"));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JLabel icon = new JLabel("📁");
            icon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 48));
            add(icon, "wrap");

            JLabel dropLabel = new JLabel("Arrastra un archivo aquí");
            dropLabel.setFont(new Font("Segoe UI", Font.BOLD, 15));
            dropLabel.setForeground(TEXT_WHITE);
            add(dropLabel, "wrap, gaptop 8");

            JLabel orLabel = new JLabel("o");
            orLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            orLabel.setForeground(TEXT_MUTED);
            add(orLabel, "wrap");

            JButton browseBtn = createAccentButton("Seleccionar Archivo", accent);
            browseBtn.addActionListener(e -> openFileChooser(source, accent));
            add(browseBtn, "gaptop 8, wrap");

            JLabel formatsLabel = new JLabel("Formatos: XLS, CSV, PDF, TXT");
            formatsLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            formatsLabel.setForeground(TEXT_MUTED);
            add(formatsLabel, "gaptop 12");

            // Drag and drop support
            new DropTarget(this, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
                @Override
                public void dragEnter(DropTargetDragEvent dtde) {
                    hovering = true;
                    repaint();
                }

                @Override
                public void dragExit(DropTargetEvent dte) {
                    hovering = false;
                    repaint();
                }

                @Override
                public void drop(DropTargetDropEvent dtde) {
                    hovering = false;
                    repaint();
                    try {
                        dtde.acceptDrop(DnDConstants.ACTION_COPY);
                        @SuppressWarnings("unchecked")
                        java.util.List<File> files = (java.util.List<File>) dtde.getTransferable()
                                .getTransferData(DataFlavor.javaFileListFlavor);
                        if (!files.isEmpty()) {
                            processFile(files.get(0), source);
                        }
                    } catch (Exception ex) {
                        Toast.show("Error al recibir archivo: " + ex.getMessage(), Toast.Type.ERROR);
                    }
                }
            }, true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Dashed border
            float[] dash = { 8f, 6f };
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, dash, 0f));
            Color borderColor = hovering ? accent : BORDER_DASHED;
            g2.setColor(borderColor);
            g2.draw(new RoundRectangle2D.Float(4, 4, getWidth() - 8, getHeight() - 8, 16, 16));

            if (hovering) {
                g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 20));
                g2.fill(new RoundRectangle2D.Float(4, 4, getWidth() - 8, getHeight() - 8, 16, 16));
            }

            g2.dispose();
        }
    }

    private void openFileChooser(Transaction.Source source, Color accent) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(source == Transaction.Source.BOOK
                ? "Seleccionar Libro Contable"
                : "Seleccionar Estado de Cuenta");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Archivos compatibles (XLS, CSV, PDF, TXT)", "xls", "xlsx", "csv", "pdf", "txt"));
        chooser.setAcceptAllFileFilterUsed(false);

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            processFile(chooser.getSelectedFile(), source);
        }
    }

    private void processFile(File file, Transaction.Source source) {
        // Run parsing in background
        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            List<Transaction> parsed = new ArrayList<>();
            double extractedSaldo = 0;
            String errorMsg = null;

            @Override
            protected Void doInBackground() {
                try {
                    FileParser parser = ParserFactory.getParser(file);
                    parsed = parser.parse(file, source);
                    if (source == Transaction.Source.BANK) {
                        extractedSaldo = parser.extractSaldoInicial(file);
                    }
                } catch (Exception ex) {
                    errorMsg = ex.getMessage();
                }
                return null;
            }

            @Override
            protected void done() {
                if (errorMsg != null) {
                    Toast.show("Error: " + errorMsg, Toast.Type.ERROR);
                    return;
                }

                if (parsed.isEmpty()) {
                    Toast.show("No se encontraron transacciones en el archivo", Toast.Type.WARNING);
                    return;
                }

                if (source == Transaction.Source.BOOK) {
                    bookTransactions = parsed;
                    bookStatusLabel.setText("✓ " + file.getName() + " — " + parsed.size() + " transacciones");
                    bookStatusLabel.setForeground(ACCENT_BLUE);
                    bookPreviewModel.setTransactions(parsed);
                    showPreview(source);
                    Toast.show("Libro Contable cargado: " + parsed.size() + " registros", Toast.Type.SUCCESS);
                } else {
                    bankTransactions = parsed;
                    bankStatusLabel.setText("✓ " + file.getName() + " — " + parsed.size() + " transacciones");
                    bankStatusLabel.setForeground(ACCENT_GREEN);
                    bankPreviewModel.setTransactions(parsed);
                    if (extractedSaldo > 0) {
                        saldoInicial = extractedSaldo;
                        saldoInicialField.setText(CURRENCY_FMT.format(extractedSaldo));
                    }
                    showPreview(source);
                    Toast.show("Estado de Cuenta cargado: " + parsed.size() + " registros", Toast.Type.SUCCESS);
                }

                // Enable Continue button if both loaded
                continueButton.setEnabled(!bookTransactions.isEmpty() && !bankTransactions.isEmpty());
            }
        };
        worker.execute();
    }

    private void showPreview(Transaction.Source source) {
        JPanel section = (source == Transaction.Source.BOOK)
                ? (JPanel) bookDropZone.getParent()
                : (JPanel) bankDropZone.getParent();
        CardLayout cl = (CardLayout) section.getLayout();
        cl.show(section, "PREVIEW");
        section.revalidate();
        section.repaint();
    }

    // ── Button Factories ──

    private JButton createAccentButton(String text, Color baseColor) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = isEnabled()
                        ? (getModel().isRollover() ? baseColor.brighter() : baseColor)
                        : new Color(60, 65, 75);
                if (getModel().isPressed())
                    bg = baseColor.darker();
                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 14, 14));
                g2.setColor(isEnabled() ? Color.WHITE : TEXT_MUTED);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                        (getHeight() + fm.getAscent()) / 2 - 3);
                g2.dispose();
            }
        };
        btn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btn.setPreferredSize(new Dimension(200, 42));
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private JButton createSmallButton(String text, Color accent) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        btn.setForeground(accent);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    // ── Preview Table Model ──

    private static class PreviewTableModel extends AbstractTableModel {
        private final String[] columns;
        private List<Transaction> transactions = new ArrayList<>();

        PreviewTableModel(String[] columns) {
            this.columns = columns;
        }

        void setTransactions(List<Transaction> txns) {
            this.transactions = txns;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return transactions.size();
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
        public boolean isCellEditable(int r, int c) {
            return false;
        }

        @Override
        public Object getValueAt(int row, int col) {
            Transaction t = transactions.get(row);
            return switch (col) {
                case 0 -> {
                    // Format as DD/MM/AAAA
                    var d = t.getDate();
                    yield String.format("%02d/%02d/%04d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
                }
                case 1 -> t.getReference();
                case 2 -> t.getDescription();
                case 3 -> t.getDeposit() == 0 ? "" : String.format("%,.2f", t.getDeposit());
                case 4 -> t.getWithdrawal() == 0 ? "" : String.format("%,.2f", t.getWithdrawal());
                default -> "";
            };
        }
    }
}
