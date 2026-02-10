package com.bankreconciliation.parser;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes column headers from various file formats into standard column
 * names.
 * Supports Spanish and English header variations.
 */
public class ColumnMapper {

    public enum Column {
        FECHA, REFERENCIA, DESCRIPCION, DEPOSITO, RETIRO, UNKNOWN
    }

    private static final Map<Column, List<String>> PATTERNS = new LinkedHashMap<>();

    static {
        PATTERNS.put(Column.FECHA, Arrays.asList(
                "fecha", "date", "fec", "f.valor", "f. valor", "fecha valor",
                "fecha operacion", "fecha contable", "f.operacion", "fch"));
        PATTERNS.put(Column.REFERENCIA, Arrays.asList(
                "ref", "referencia", "reference", "nro", "numero", "comprobante",
                "nro. doc", "nro.doc", "nro doc", "num", "documento", "doc",
                "n° comprobante", "no. referencia"));
        PATTERNS.put(Column.DESCRIPCION, Arrays.asList(
                "descripcion", "descripción", "description", "concepto", "detalle",
                "observacion", "observación", "movimiento", "glosa", "nota",
                "concepto/descripcion"));
        PATTERNS.put(Column.DEPOSITO, Arrays.asList(
                "deposito", "depósito", "deposit", "credito", "crédito", "credit",
                "ingreso", "abono", "debe", "haber", "debito", "débito",
                "monto credito", "monto crédito", "deposits"));
        PATTERNS.put(Column.RETIRO, Arrays.asList(
                "retiro", "withdrawal", "egreso", "cargo", "pago",
                "monto debito", "monto débito", "debit", "withdrawals",
                "cargos", "pagos"));
    }

    /**
     * Maps an array of raw header strings to their normalized Column types.
     *
     * @param headers raw headers from file
     * @return map of column index → Column type
     */
    public static Map<Integer, Column> mapHeaders(String[] headers) {
        Map<Integer, Column> mapping = new LinkedHashMap<>();
        Set<Column> usedColumns = new HashSet<>();

        for (int i = 0; i < headers.length; i++) {
            String normalized = normalize(headers[i]);
            Column matched = matchColumn(normalized, usedColumns);
            mapping.put(i, matched);
            if (matched != Column.UNKNOWN) {
                usedColumns.add(matched);
            }
        }
        return mapping;
    }

    /**
     * Checks if a set of headers contains enough recognizable columns to be valid.
     */
    public static boolean isValidHeaderRow(String[] headers) {
        Map<Integer, Column> mapped = mapHeaders(headers);
        long knownCount = mapped.values().stream()
                .filter(c -> c != Column.UNKNOWN)
                .count();
        // Need at least Fecha + one amount column
        return knownCount >= 2 && mapped.containsValue(Column.FECHA);
    }

    private static Column matchColumn(String normalized, Set<Column> usedColumns) {
        for (Map.Entry<Column, List<String>> entry : PATTERNS.entrySet()) {
            if (usedColumns.contains(entry.getKey()))
                continue;
            for (String pattern : entry.getValue()) {
                if (normalized.contains(pattern) || pattern.contains(normalized)) {
                    return entry.getKey();
                }
            }
        }
        return Column.UNKNOWN;
    }

    private static String normalize(String header) {
        if (header == null)
            return "";
        return header.toLowerCase()
                .trim()
                .replaceAll("[áà]", "a")
                .replaceAll("[éè]", "e")
                .replaceAll("[íì]", "i")
                .replaceAll("[óò]", "o")
                .replaceAll("[úù]", "u")
                .replaceAll("[^a-z0-9./ ]", "")
                .trim();
    }

    /**
     * Tries to extract a "Saldo Inicial" value from a line of text.
     *
     * @param line a line of text
     * @return the parsed amount, or null if not found
     */
    public static Double extractSaldoInicial(String line) {
        if (line == null)
            return null;
        String lower = line.toLowerCase()
                .replaceAll("[áà]", "a")
                .replaceAll("[éè]", "e")
                .replaceAll("[íì]", "i")
                .replaceAll("[óò]", "o")
                .replaceAll("[úù]", "u");

        if (lower.contains("saldo inicial") || lower.contains("balance anterior")
                || lower.contains("saldo anterior") || lower.contains("opening balance")
                || lower.contains("saldo al inicio")) {
            // Extract numeric value
            Pattern p = Pattern.compile("[\\d][\\d.,]*[\\d]");
            Matcher m = p.matcher(line);
            if (m.find()) {
                return parseAmount(m.group());
            }
        }
        return null;
    }

    /**
     * Parse a numeric string that may use . or , for thousands/decimals.
     */
    public static double parseAmount(String raw) {
        if (raw == null || raw.isBlank())
            return 0.0;
        raw = raw.trim().replaceAll("[^\\d.,-]", "");
        if (raw.isEmpty())
            return 0.0;

        // Determine format: 1.234,56 (European) vs 1,234.56 (US)
        int lastDot = raw.lastIndexOf('.');
        int lastComma = raw.lastIndexOf(',');

        if (lastComma > lastDot) {
            // European: dots are thousands, comma is decimal
            raw = raw.replace(".", "").replace(",", ".");
        } else {
            // US: commas are thousands, dot is decimal
            raw = raw.replace(",", "");
        }

        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
