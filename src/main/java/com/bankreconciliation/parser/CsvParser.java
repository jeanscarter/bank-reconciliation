package com.bankreconciliation.parser;

import com.bankreconciliation.model.Transaction;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.*;

/**
 * Parses .csv files with auto-detection of separator character.
 */
public class CsvParser implements FileParser {

    @Override
    public List<Transaction> parse(File file, Transaction.Source source) throws Exception {
        List<Transaction> transactions = new ArrayList<>();
        List<String> lines = Files.readAllLines(file.toPath(), detectCharset(file));

        if (lines.isEmpty()) {
            throw new Exception("El archivo CSV está vacío.");
        }

        char separator = detectSeparator(lines);
        int headerIdx = -1;
        Map<Integer, ColumnMapper.Column> columnMap = null;

        // Find header row (scan first 15 lines)
        for (int i = 0; i < Math.min(15, lines.size()); i++) {
            String[] cells = splitLine(lines.get(i), separator);
            if (ColumnMapper.isValidHeaderRow(cells)) {
                headerIdx = i;
                columnMap = ColumnMapper.mapHeaders(cells);
                break;
            }
        }

        if (headerIdx < 0 || columnMap == null) {
            throw new Exception("No se encontró una fila de encabezado válida en el archivo CSV.");
        }

        // Parse data rows
        for (int i = headerIdx + 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty())
                continue;

            String[] cells = splitLine(line, separator);
            Transaction t = parseRow(cells, columnMap, source);
            if (t != null) {
                transactions.add(t);
            }
        }

        return transactions;
    }

    @Override
    public double extractSaldoInicial(File file) {
        try {
            List<String> lines = Files.readAllLines(file.toPath(), detectCharset(file));
            for (int i = 0; i < Math.min(15, lines.size()); i++) {
                Double saldo = ColumnMapper.extractSaldoInicial(lines.get(i));
                if (saldo != null)
                    return saldo;
            }
        } catch (Exception ignored) {
        }
        return 0.0;
    }

    private Transaction parseRow(String[] cells, Map<Integer, ColumnMapper.Column> columnMap,
            Transaction.Source source) {
        LocalDate date = null;
        String reference = "";
        String description = "";
        double deposit = 0;
        double withdrawal = 0;

        for (Map.Entry<Integer, ColumnMapper.Column> entry : columnMap.entrySet()) {
            int idx = entry.getKey();
            if (idx >= cells.length)
                continue;
            String val = cells[idx].trim();

            switch (entry.getValue()) {
                case FECHA -> date = parseDate(val);
                case REFERENCIA -> reference = val;
                case DESCRIPCION -> description = val;
                case DEPOSITO -> deposit = Math.abs(ColumnMapper.parseAmount(val));
                case RETIRO -> withdrawal = Math.abs(ColumnMapper.parseAmount(val));
                default -> {
                }
            }
        }

        if (date == null)
            return null;
        if (deposit == 0 && withdrawal == 0)
            return null;

        return new Transaction(date, reference, description, deposit, withdrawal, source);
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank())
            return null;
        s = s.trim().replaceAll("\"", "");
        String[] parts = s.split("[/\\-.]");
        if (parts.length == 3) {
            try {
                int a = Integer.parseInt(parts[0].trim());
                int b = Integer.parseInt(parts[1].trim());
                int c = Integer.parseInt(parts[2].trim());
                if (c > 100)
                    return LocalDate.of(c, b, a); // DD/MM/YYYY
                if (a > 100)
                    return LocalDate.of(a, b, c); // YYYY/MM/DD
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private char detectSeparator(List<String> lines) {
        char[] candidates = { ',', ';', '\t', '|' };
        int[] counts = new int[candidates.length];

        int samplesToCheck = Math.min(10, lines.size());
        for (int i = 0; i < samplesToCheck; i++) {
            for (int j = 0; j < candidates.length; j++) {
                counts[j] += countOccurrences(lines.get(i), candidates[j]);
            }
        }

        int maxIdx = 0;
        for (int j = 1; j < candidates.length; j++) {
            if (counts[j] > counts[maxIdx])
                maxIdx = j;
        }
        return candidates[maxIdx];
    }

    private int countOccurrences(String line, char c) {
        int count = 0;
        for (char ch : line.toCharArray()) {
            if (ch == c)
                count++;
        }
        return count;
    }

    private String[] splitLine(String line, char separator) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == separator && !inQuotes) {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[0]);
    }

    private java.nio.charset.Charset detectCharset(File file) {
        // Try UTF-8 first, fallback to ISO-8859-1
        try {
            Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            return StandardCharsets.UTF_8;
        } catch (Exception e) {
            return StandardCharsets.ISO_8859_1;
        }
    }
}
