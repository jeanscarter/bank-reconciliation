package com.bankreconciliation.util;

import com.bankreconciliation.model.Transaction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility to calculate recurring charges (COM, MANT, etc.) from a list of
 * transactions.
 */
public class RecurringChargesCalculator {

    public static Map<String, Double> calculate(List<Transaction> transactions) {
        Map<String, Double> recurringMap = new HashMap<>();

        // Initialize keys to 0.0
        recurringMap.put("COMISIONES", 0.0);
        recurringMap.put("MANTENIMIENTO", 0.0);
        recurringMap.put("ITF", 0.0);
        recurringMap.put("IVA", 0.0);
        recurringMap.put("CHEQUERA", 0.0);

        for (Transaction t : transactions) {
            String desc = t.getDescription() == null ? "" : t.getDescription().toUpperCase();
            String key = null;

            if (desc.startsWith("COM"))
                key = "COMISIONES";
            else if (desc.startsWith("MANT"))
                key = "MANTENIMIENTO";
            else if (desc.startsWith("ITF"))
                key = "ITF";
            else if (desc.startsWith("IVA"))
                key = "IVA";
            else if (desc.contains("CHEQUERA"))
                key = "CHEQUERA";

            if (key != null) {
                recurringMap.put(key, recurringMap.get(key) + t.getAbsAmount());
            }
        }
        return recurringMap;
    }
}
