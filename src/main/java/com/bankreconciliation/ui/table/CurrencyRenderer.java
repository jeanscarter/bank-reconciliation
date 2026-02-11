package com.bankreconciliation.ui.table;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public class CurrencyRenderer extends DefaultTableCellRenderer {

    private static final DecimalFormat FMT;

    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setGroupingSeparator(',');
        symbols.setDecimalSeparator('.');
        FMT = new DecimalFormat("#,##0.00", symbols);
    }

    private final boolean highlightNonZero;

    public CurrencyRenderer(boolean highlightNonZero) {
        this.highlightNonZero = highlightNonZero;
        setHorizontalAlignment(SwingConstants.RIGHT);
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
            boolean isSelected, boolean hasFocus, int row, int column) {
        Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

        if (value instanceof Number num) {
            double val = num.doubleValue();
            setText(val == 0 ? "" : FMT.format(val));
            if (!isSelected && highlightNonZero && val > 0) {
                setForeground(new Color(76, 175, 80));
            } else if (!isSelected && highlightNonZero && val < 0) {
                setForeground(new Color(244, 67, 54));
            } else if (!isSelected) {
                setForeground(table.getForeground());
            }
        }
        return c;
    }
}
