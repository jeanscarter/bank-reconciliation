package com.bankreconciliation;

import com.bankreconciliation.model.Transaction;
import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class App {

    public static void main(String[] args) {
        // Setup FlatLaf Dark theme
        FlatDarkLaf.setup();
        UIManager.put("Component.arc", 12);
        UIManager.put("Button.arc", 14);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new java.awt.Insets(2, 2, 2, 2));
        UIManager.put("TabbedPane.selectedBackground", new java.awt.Color(40, 44, 52));
        UIManager.put("Table.showHorizontalLines", false);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("TableHeader.separatorColor", new java.awt.Color(50, 55, 65));
        UIManager.put("ScrollPane.smoothScrolling", true);

        // Generate mock data
        List<Transaction> bookTransactions = generateBookTransactions();
        List<Transaction> bankTransactions = generateBankTransactions();

        // Launch
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame(bookTransactions, bankTransactions);
            frame.setVisible(true);
        });
    }

    private static List<Transaction> generateBookTransactions() {
        List<Transaction> list = new ArrayList<>();
        Random r = new Random(42);
        LocalDate base = LocalDate.of(2026, 1, 5);

        // Matched entries (will have counterparts in bank)
        list.add(new Transaction(base, "CHK-1001", "Pago proveedor Distribuidora Norte",
                0, 15000.00, Transaction.Source.BOOK));
        list.add(new Transaction(base.plusDays(1), "DEP-2001", "Depósito cliente Farmacias ABC",
                25000.00, 0, Transaction.Source.BOOK));
        list.add(new Transaction(base.plusDays(3), "CHK-1002", "Pago nómina quincenal",
                0, 48500.00, Transaction.Source.BOOK));
        list.add(new Transaction(base.plusDays(4), "DEP-2002", "Transferencia por venta de activo",
                12750.00, 0, Transaction.Source.BOOK));
        list.add(new Transaction(base.plusDays(5), "CHK-1003", "Pago servicios de limpieza",
                0, 3200.00, Transaction.Source.BOOK));
        list.add(new Transaction(base.plusDays(7), "DEP-2003", "Cobro factura #F-4521",
                8900.00, 0, Transaction.Source.BOOK));
        list.add(new Transaction(base.plusDays(8), "CHK-1004", "Pago alquiler oficina central",
                0, 22000.00, Transaction.Source.BOOK));
        list.add(new Transaction(base.plusDays(10), "DEP-2004", "Ingreso por consultoría",
                35600.00, 0, Transaction.Source.BOOK));

        // Unmatched entries (transit or exceptions)
        list.add(new Transaction(base.plusDays(12), "CHK-1005", "Cheque pendiente por cobrar",
                0, 7500.00, Transaction.Source.BOOK));
        list.add(new Transaction(base.plusDays(14), "DEP-2005", "Depósito en tránsito - Cobranza",
                18200.00, 0, Transaction.Source.BOOK));
        list.add(new Transaction(base.plusDays(15), "CHK-1006", "Pago de seguro empresarial",
                0, 5400.00, Transaction.Source.BOOK));
        list.add(new Transaction(base.plusDays(17), "DEP-2006", "Ingreso por intereses ganados",
                1250.50, 0, Transaction.Source.BOOK));

        return list;
    }

    private static List<Transaction> generateBankTransactions() {
        List<Transaction> list = new ArrayList<>();
        LocalDate base = LocalDate.of(2026, 1, 5);

        // Matched entries (same amounts as book counterparts)
        list.add(new Transaction(base.plusDays(1), "BC-5001", "Cargo cheque #1001",
                0, 15000.00, Transaction.Source.BANK));
        list.add(new Transaction(base.plusDays(2), "BC-5002", "Abono transferencia recibida",
                25000.00, 0, Transaction.Source.BANK));
        list.add(new Transaction(base.plusDays(4), "BC-5003", "Cargo nómina dispersión",
                0, 48500.00, Transaction.Source.BANK));
        list.add(new Transaction(base.plusDays(5), "BC-5004", "Abono transferencia activo",
                12750.00, 0, Transaction.Source.BANK));
        list.add(new Transaction(base.plusDays(6), "BC-5005", "Cargo cheque #1003",
                0, 3200.00, Transaction.Source.BANK));
        list.add(new Transaction(base.plusDays(8), "BC-5006", "Abono cobro factura",
                8900.00, 0, Transaction.Source.BANK));
        list.add(new Transaction(base.plusDays(9), "BC-5007", "Cargo renta oficina",
                0, 22000.00, Transaction.Source.BANK));
        list.add(new Transaction(base.plusDays(11), "BC-5008", "Abono consultoría",
                35600.00, 0, Transaction.Source.BANK));

        // Bank-only entries (commissions, fees, etc.)
        list.add(new Transaction(base.plusDays(13), "BC-5009", "Comisión bancaria mensual",
                0, 850.00, Transaction.Source.BANK));
        list.add(new Transaction(base.plusDays(16), "BC-5010", "Comisión por transferencia SPEI",
                0, 120.00, Transaction.Source.BANK));
        list.add(new Transaction(base.plusDays(18), "BC-5011", "Intereses pagados por sobregiro",
                0, 2340.75, Transaction.Source.BANK));
        list.add(new Transaction(base.plusDays(20), "BC-5012", "Abono por error corregido",
                950.00, 0, Transaction.Source.BANK));

        return list;
    }
}
