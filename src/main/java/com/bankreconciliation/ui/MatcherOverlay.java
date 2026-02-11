package com.bankreconciliation.ui;

import com.bankreconciliation.model.Transaction;
import com.bankreconciliation.ui.table.CurrencyRenderer;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class MatcherOverlay extends RoundedPanel {

    private final Transaction source;
    private final List<Transaction> candidates;
    private final Consumer<Transaction> onMatch;
    private final Runnable onStatusAssign;

    public MatcherOverlay(Transaction source, List<Transaction> allOpposite,
            Consumer<Transaction> onMatch, Runnable onStatusAssign) {
        super(24);
        this.source = source;
        this.onMatch = onMatch;
        this.onStatusAssign = onStatusAssign;

        this.candidates = new ArrayList<>();
        double targetAbs = source.getAbsAmount();
        for (Transaction t : allOpposite) {
            if (t.isPending() && Math.abs(t.getAbsAmount() - targetAbs) < 0.01) {
                candidates.add(t);
            }
        }

        setBackground(new Color(40, 44, 52));
        setPreferredSize(new Dimension(620, 520));
        setLayout(new MigLayout("insets 28, fillx, wrap", "[grow]", ""));

        addMouseListener(new MouseAdapter() {
        }); // block click-through

        buildUI();
    }

    private void buildUI() {
        // Close button
        JButton closeBtn = new JButton("✕") {
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
        closeBtn.setPreferredSize(new Dimension(32, 32));
        closeBtn.setContentAreaFilled(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setFocusPainted(false);
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.addActionListener(e -> ModalManager.dismiss(null));
        add(closeBtn, "pos (100%-48) 12, w 32!, h 32!");

        // Header
        JLabel header = new JLabel("Búsqueda de Coincidencias");
        header.setFont(new Font("Segoe UI", Font.BOLD, 20));
        header.setForeground(Color.WHITE);
        add(header, "gapbottom 8");

        // Source info card
        RoundedPanel sourceCard = new RoundedPanel(16, false);
        sourceCard.setBackground(new Color(50, 55, 65));
        sourceCard.setLayout(new MigLayout("insets 16, fillx", "[grow][]", ""));

        JLabel srcLabel = new JLabel("Transacción Seleccionada");
        srcLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        srcLabel.setForeground(new Color(130, 170, 255));
        sourceCard.add(srcLabel, "span, wrap, gapbottom 6");

        JLabel srcDesc = new JLabel(source.getDescription());
        srcDesc.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        srcDesc.setForeground(Color.WHITE);
        sourceCard.add(srcDesc);

        double amt = source.getAbsAmount();
        JLabel srcAmt = new JLabel(String.format("Bs. %,.2f", amt));
        srcAmt.setFont(new Font("Segoe UI", Font.BOLD, 18));
        srcAmt.setForeground(source.getDeposit() > 0 ? new Color(76, 175, 80) : new Color(244, 67, 54));
        sourceCard.add(srcAmt, "align right");

        JLabel srcRef = new JLabel("Ref: " + source.getReference() + "   |   " + source.getDate());
        srcRef.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        srcRef.setForeground(new Color(160, 160, 160));
        sourceCard.add(srcRef, "newline, span");

        add(sourceCard, "growx, gapbottom 12");

        // Candidates or manual status
        if (candidates.isEmpty()) {
            buildNoMatchUI();
        } else {
            buildMatchTableUI();
        }
    }

    private void buildNoMatchUI() {
        JLabel noMatch = new JLabel("No se encontraron coincidencias por monto.");
        noMatch.setFont(new Font("Segoe UI", Font.ITALIC, 13));
        noMatch.setForeground(new Color(255, 152, 0));
        add(noMatch, "gapbottom 16");

        JLabel assignLabel = new JLabel("Asignar estado manualmente:");
        assignLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        assignLabel.setForeground(Color.WHITE);
        add(assignLabel, "gapbottom 8");

        JPanel btnRow = new JPanel(new MigLayout("insets 0, gap 8", "", ""));
        btnRow.setOpaque(false);

        Transaction.Status[] statuses = {
                Transaction.Status.DNA, Transaction.Status.RNE,
                Transaction.Status.ANR, Transaction.Status.CNR
        };
        for (Transaction.Status st : statuses) {
            JButton btn = createStyledButton(st.getLabel(), st.getColor());
            btn.addActionListener(e -> {
                source.setStatus(st);
                ModalManager.dismiss(() -> {
                    Toast.show("Estado asignado: " + st.getLabel(), Toast.Type.WARNING);
                    if (onStatusAssign != null)
                        onStatusAssign.run();
                });
            });
            btnRow.add(btn);
        }
        add(btnRow, "growx");
    }

    private void buildMatchTableUI() {
        JLabel matchLabel = new JLabel(candidates.size() + " coincidencia(s) encontrada(s):");
        matchLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        matchLabel.setForeground(new Color(76, 175, 80));
        add(matchLabel, "gapbottom 6");

        String[] cols = { "Fecha", "Referencia", "Descripción", "Monto" };
        Object[][] data = new Object[candidates.size()][4];
        for (int i = 0; i < candidates.size(); i++) {
            Transaction c = candidates.get(i);
            data[i][0] = c.getDate().toString();
            data[i][1] = c.getReference();
            data[i][2] = c.getDescription();
            data[i][3] = c.getAbsAmount();
        }

        JTable matchTable = new JTable(new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return data.length;
            }

            @Override
            public int getColumnCount() {
                return cols.length;
            }

            @Override
            public String getColumnName(int c) {
                return cols[c];
            }

            @Override
            public Object getValueAt(int r, int c) {
                return data[r][c];
            }

            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        });

        matchTable.setRowHeight(36);
        matchTable.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        matchTable.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
        matchTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        matchTable.getColumnModel().getColumn(3).setCellRenderer(new CurrencyRenderer(true));
        matchTable.setFillsViewportHeight(true);
        matchTable.putClientProperty("FlatLaf.style", "arc: 10");

        JScrollPane scroll = new JScrollPane(matchTable);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(new Color(40, 44, 52));
        add(scroll, "growx, h 160!, gapbottom 12");

        JPanel actionRow = new JPanel(new MigLayout("insets 0, gap 12", "push[][]", ""));
        actionRow.setOpaque(false);

        JButton cancelBtn = createStyledButton("Cancelar", new Color(120, 120, 120));
        cancelBtn.addActionListener(e -> ModalManager.dismiss(null));
        actionRow.add(cancelBtn);

        JButton confirmBtn = createStyledButton("Conciliar", new Color(46, 125, 50));
        confirmBtn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        confirmBtn.addActionListener(e -> {
            int selectedRow = matchTable.getSelectedRow();
            if (selectedRow < 0) {
                Toast.show("Seleccione una coincidencia", Toast.Type.WARNING);
                return;
            }
            Transaction matched = candidates.get(selectedRow);
            onMatch.accept(matched);
        });
        actionRow.add(confirmBtn);

        add(actionRow, "growx");
    }

    private JButton createStyledButton(String text, Color baseColor) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                Color bg = getModel().isRollover()
                        ? baseColor.brighter()
                        : baseColor;
                if (getModel().isPressed()) {
                    bg = baseColor.darker();
                }

                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 14, 14));

                g2.setColor(Color.WHITE);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int textX = (getWidth() - fm.stringWidth(getText())) / 2;
                int textY = (getHeight() + fm.getAscent()) / 2 - 3;
                g2.drawString(getText(), textX, textY);

                g2.dispose();
            }
        };
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setPreferredSize(new Dimension(110, 36));
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }
}
