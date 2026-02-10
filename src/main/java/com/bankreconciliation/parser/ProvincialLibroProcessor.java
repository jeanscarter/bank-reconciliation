package com.bankreconciliation.parser;

import com.bankreconciliation.model.Transaction;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Specialized parser for Banco Provincial "Libro Contable" CSV export files.
 *
 * <p>
 * The Provincial export is NOT a flat table — it is a report with interleaved
 * metadata rows:
 * <ul>
 * <li><b>Date control rows:</b> A single Excel serial number (e.g.
 * {@code 45992.0})
 * in column 0 with all other columns empty. This "sticky date" applies to all
 * subsequent transactions until the next date-control row.</li>
 * <li><b>Transaction rows:</b> Begin with a zero-padded ID (e.g.
 * {@code 0000017835}),
 * have a populated Nro. Doc. column, and are not anuladas.</li>
 * <li><b>Discard rows:</b> Headers, sub-totals, closing separators, and rows
 * whose
 * description is exactly {@code *ANULADO*}.</li>
 * </ul>
 *
 * <h3>Column mapping (0-indexed):</h3>
 * 
 * <pre>
 *   0 → Número (line ID / serial date)
 *   3 → Tipo (TP, TR, DP — informational only)
 *   4 → Nro. Doc.   → Reference
 *   6 → Descripción → Description
 *   8 → Debe        → Deposit  (increments balance for Libro Contable)
 *   9 → Haber       → Withdrawal (decrements balance for Libro Contable)
 * </pre>
 *
 * Columns 1, 2, 5, 7 (Origen), and 10 (I.G.T.F.) are intentionally ignored.
 */
public class ProvincialLibroProcessor implements FileParser {

    // Excel epoch: December 30, 1899
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    // Column indices in the Provincial CSV
    private static final int COL_NUMERO = 0;
    private static final int COL_TIPO = 3;
    private static final int COL_NRO_DOC = 4;
    private static final int COL_DESCRIPCION = 6;
    private static final int COL_DEBE = 8;
    private static final int COL_HABER = 9;

    // ── FileParser interface ─────────────────────────────────────────────────

    @Override
    public List<Transaction> parse(File file, Transaction.Source source) throws Exception {
        List<String> lines = readAllLines(file);
        return processLines(lines, source);
    }

    @Override
    public double extractSaldoInicial(File file) {
        // Provincial Libro Contable exports do not include an explicit "Saldo Inicial"
        return 0.0;
    }

    // ── Static detection helper ──────────────────────────────────────────────

    /**
     * Heuristic check: does this CSV look like a Banco Provincial Libro Contable
     * export? We inspect the first 5 non-empty lines for the signature header
     * fragment {@code "Número"} combined with {@code "Nro. Doc."} or a serial-date
     * row pattern.
     */
    public static boolean isProvincialFormat(File file) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), detectCharset(file)))) {
            int checked = 0;
            String line;
            while ((line = br.readLine()) != null && checked < 10) {
                String trimmed = line.trim();
                if (trimmed.isEmpty())
                    continue;
                checked++;
                // Check for Provincial header signature
                if (trimmed.contains("Número") && trimmed.contains("Nro. Doc.")) {
                    return true;
                }
                // Check for the characteristic column pattern: "Número,Cuenta,..."
                if (trimmed.startsWith("Número,Cuenta") || trimmed.startsWith("\"Número\",\"Cuenta\"")) {
                    return true;
                }
                // Check for serial-date row (single numeric value like 45992.0)
                if (isSerialDateRow(splitCSV(trimmed))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ── Core processing logic ────────────────────────────────────────────────

    private List<Transaction> processLines(List<String> lines, Transaction.Source source) {
        List<Transaction> transactions = new ArrayList<>();
        LocalDate currentDate = null;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty())
                continue;

            String[] cells = splitCSV(line);

            // ─── Classification: which kind of row is this? ───

            // 1. DISCARD: header row
            if (isHeaderRow(cells))
                continue;

            // 2. DISCARD: sub-total/closing row
            if (isSubTotalOrClosingRow(cells))
                continue;

            // 3. DATE CONTROL: serial date row
            if (isSerialDateRow(cells)) {
                try {
                    currentDate = parseSerialDate(cells[0]);
                } catch (NumberFormatException e) {
                    // Invalid serial — skip but keep processing
                }
                continue;
            }

            // 4. DISCARD: *ANULADO* transactions
            if (isAnulado(cells))
                continue;

            // 5. TRANSACTION: valid data row
            if (isValidTransaction(cells)) {
                if (currentDate == null)
                    continue; // No date context yet — skip

                Transaction txn = buildTransaction(cells, currentDate, source);
                if (txn != null) {
                    transactions.add(txn);
                }
            }
        }

        return transactions;
    }

    // ── Row classifiers ──────────────────────────────────────────────────────

    /**
     * Header row: starts with "Número" or contains "Cuenta" at expected positions.
     */
    private boolean isHeaderRow(String[] cells) {
        if (cells.length == 0)
            return false;
        String first = cleanField(cells[0]);
        return "Número".equalsIgnoreCase(first)
                || "Numero".equalsIgnoreCase(first)
                || first.startsWith("Número");
    }

    /**
     * Sub-total or closing separator row.
     * - Column 7 contains "Sub-Totales:"
     * - Or all columns empty except column 0 with a high serial value acting as
     * footer
     */
    private boolean isSubTotalOrClosingRow(String[] cells) {
        if (cells.length > 7) {
            String col7 = cleanField(cells[7]);
            if (col7.contains("Sub-Totales") || col7.contains("Sub-Total")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Date-control row: column 0 has a numeric value with decimal (e.g. 45992.0),
     * and all other meaningful columns are empty or null.
     */
    private static boolean isSerialDateRow(String[] cells) {
        if (cells.length == 0)
            return false;
        String first = cleanField(cells[0]);
        if (first.isEmpty())
            return false;

        // Must look like a decimal number (Excel serial date)
        if (!first.matches("\\d{4,6}\\.\\d+"))
            return false;

        // Remaining columns should be empty
        for (int i = 1; i < cells.length; i++) {
            if (!cleanField(cells[i]).isEmpty())
                return false;
        }
        return true;
    }

    /**
     * Transaction is anulada if description (column 6) is exactly "*ANULADO*".
     */
    private boolean isAnulado(String[] cells) {
        if (cells.length <= COL_DESCRIPCION)
            return false;
        String desc = cleanField(cells[COL_DESCRIPCION]);
        return "*ANULADO*".equals(desc);
    }

    /**
     * Valid transaction: column 0 has an ID-like format (numeric, possibly
     * zero-padded),
     * and Nro. Doc. (column 4) is not empty.
     */
    private boolean isValidTransaction(String[] cells) {
        if (cells.length <= COL_HABER)
            return false;
        String id = cleanField(cells[COL_NUMERO]);
        String nroDoc = cleanField(cells[COL_NRO_DOC]);
        // ID should be numeric (zero-padded like 0000017835)
        return !id.isEmpty() && id.matches("\\d+") && !nroDoc.isEmpty();
    }

    // ── Transaction builder ──────────────────────────────────────────────────

    private Transaction buildTransaction(String[] cells, LocalDate date, Transaction.Source source) {
        try {
            String reference = cleanField(cells[COL_NRO_DOC]);
            if (reference.isEmpty())
                reference = "S/N";

            String description = cleanField(cells[COL_DESCRIPCION]);

            double debe = parseAmount(cells[COL_DEBE]); // Deposit for Libro Contable
            double haber = parseAmount(cells[COL_HABER]); // Withdrawal for Libro Contable

            return new Transaction(date, reference, description, debe, haber, source);
        } catch (Exception e) {
            // Malformed row — skip silently
            return null;
        }
    }

    // ── Date conversion ──────────────────────────────────────────────────────

    /**
     * Converts an Excel serial date string to {@link LocalDate}.
     * Excel epoch = December 30, 1899.
     * e.g. 45992.0 → 2025-12-31
     */
    private LocalDate parseSerialDate(String rawValue) throws NumberFormatException {
        String cleaned = cleanField(rawValue);
        double serialDouble = Double.parseDouble(cleaned);
        long serialDays = (long) serialDouble;
        return EXCEL_EPOCH.plusDays(serialDays);
    }

    // ── Amount parsing ───────────────────────────────────────────────────────

    /**
     * Cleans and parses a monetary amount:
     * <ol>
     * <li>Remove surrounding quotes</li>
     * <li>Remove thousand-separator commas</li>
     * <li>If empty, return 0.0</li>
     * </ol>
     */
    private double parseAmount(String raw) {
        String cleaned = cleanField(raw);
        if (cleaned.isEmpty())
            return 0.0;

        // Remove thousand-separator commas: "1,234,567.89" → "1234567.89"
        cleaned = cleaned.replace(",", "");

        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    // ── CSV field utilities ──────────────────────────────────────────────────

    /**
     * Splits a CSV line respecting double-quote text qualifiers.
     * Handles: {@code "field with, comma",normal,""empty""}
     */
    static String[] splitCSV(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    // Escaped quote inside quoted field
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString()); // last field

        return fields.toArray(new String[0]);
    }

    /**
     * Strips surrounding whitespace and double quotes from a field value.
     */
    private static String cleanField(String raw) {
        if (raw == null)
            return "";
        String s = raw.trim();
        // Remove surrounding double quotes
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    // ── File I/O ─────────────────────────────────────────────────────────────

    private List<String> readAllLines(File file) throws IOException {
        Charset charset = detectCharset(file);
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), charset))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * Tries UTF-8 first; falls back to ISO-8859-1 if BOM or encoding markers
     * suggest it.
     */
    private static Charset detectCharset(File file) {
        try (InputStream is = new FileInputStream(file)) {
            byte[] bom = new byte[3];
            int read = is.read(bom);
            if (read >= 3 && bom[0] == (byte) 0xEF && bom[1] == (byte) 0xBB && bom[2] == (byte) 0xBF) {
                return StandardCharsets.UTF_8;
            }
        } catch (IOException ignored) {
        }

        // Quick heuristic: if file contains bytes > 0x7F that aren't valid UTF-8,
        // assume Latin-1
        try (InputStream is = new FileInputStream(file)) {
            byte[] sample = new byte[4096];
            int read = is.read(sample);
            for (int i = 0; i < read; i++) {
                int b = sample[i] & 0xFF;
                if (b > 0x7F) {
                    // Check if it could be a valid UTF-8 multi-byte sequence
                    if ((b & 0xE0) == 0xC0 && i + 1 < read && (sample[i + 1] & 0xC0) == 0x80) {
                        i++;
                        continue;
                    }
                    // Not valid UTF-8 — likely Latin-1
                    return Charset.forName("ISO-8859-1");
                }
            }
        } catch (IOException ignored) {
        }

        return StandardCharsets.UTF_8;
    }
}
