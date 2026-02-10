package com.bankreconciliation.parser;

import com.bankreconciliation.model.Transaction;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Specialized parser for BBVA Provincial bank statement PDFs
 * ("ESTADO DE CUENTA CORRIENTE").
 *
 * <h3>Key behaviors:</h3>
 * <ul>
 * <li>Detects the document by header text: "BBVA Provincial",
 * "ESTADO DE CUENTA".</li>
 * <li>Extracts <b>Saldo Anterior</b> from the first transaction line whose
 * CONCEPTO contains "SALDO ANTERIOR".</li>
 * <li>Maps columns: F. OPER. → date, REF. → reference, CONCEPTO → description,
 * CARGOS → withdrawal, ABONOS → deposit.</li>
 * <li>Handles <b>multi-line descriptions</b> by looking ahead for continuation
 * lines that do not start with a date.</li>
 * <li>Handles OCR artifacts: dash ({@code -}) used as decimal separator
 * (e.g. {@code 3,087,990-20} → {@code 3087990.20}).</li>
 * <li>Ignores repeated column headers, page footers, and the SALDO column.</li>
 * <li>Uses {@link BigDecimal} internally for monetary precision.</li>
 * </ul>
 */
public class ProvincialBankStatementProcessor implements FileParser {

    // ── Date format: DD-MM-YYYY ──────────────────────────────────────────────
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    // ── Line-level patterns ──────────────────────────────────────────────────

    /**
     * Matches a transaction line that starts with a date DD-MM-YYYY.
     * Captures: (1) date, (2) remainder of line.
     */
    private static final Pattern TXN_LINE = Pattern.compile(
            "^(\\d{2}-\\d{2}-\\d{4})\\s+(.+)$");

    /**
     * Matches a monetary amount, including OCR artifacts:
     * <ul>
     * <li>{@code 3,087,990.20} — normal</li>
     * <li>{@code 3,087,990-20} — dash as decimal (OCR)</li>
     * <li>{@code 12,365.00} or {@code 37.09}</li>
     * </ul>
     * Captures the full amount string for further cleaning.
     */
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(\\d{1,3}(?:,\\d{3})*(?:[.\\-]\\d{2}))");

    /** Footer / noise patterns to discard. */
    private static final Pattern FOOTER_PATTERN = Pattern.compile(
            "^\\d{2}/\\d{2}/\\d{4}\\s+\\d{2}[.]\\d{2}|^Pág(?:ina)?[.:]?\\s*\\d+"
                    + "|^Página\\s+\\d+|^\\s*\\d+\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** Column header row — repeats on each page. */
    private static final Pattern HEADER_ROW = Pattern.compile(
            "F\\.?\\s*OPER|REF\\.?|CONCEPTO|F\\.?\\s*VALOR|CARGOS|ABONOS|SALDO",
            Pattern.CASE_INSENSITIVE);

    /** Bank identification keywords. */
    private static final String[] BANK_KEYWORDS = {
            "BBVA Provincial",
            "ESTADO DE CUENTA"
    };

    // ── FileParser interface ─────────────────────────────────────────────────

    @Override
    public List<Transaction> parse(File file, Transaction.Source source) throws Exception {
        String fullText = extractText(file);
        String[] allLines = fullText.split("\\r?\\n");
        return processLines(allLines, source);
    }

    @Override
    public double extractSaldoInicial(File file) {
        try {
            String text = extractText(file);
            String[] lines = text.split("\\r?\\n");

            // Strategy 1: Look for "SALDO ANTERIOR" in description
            for (String line : lines) {
                if (line.toUpperCase().contains("SALDO ANTERIOR")) {
                    // Extract amounts from this line
                    List<BigDecimal> amounts = extractAmounts(line);
                    if (!amounts.isEmpty()) {
                        // The saldo anterior amount is typically the first or the ABONOS column
                        return amounts.get(amounts.size() - 1).doubleValue();
                    }
                }
            }

            // Strategy 2: Look for "Saldo Inicial" or "Saldo al" patterns
            for (String line : lines) {
                String upper = line.toUpperCase().trim();
                if (upper.contains("SALDO INICIAL") || upper.contains("SALDO AL")) {
                    List<BigDecimal> amounts = extractAmounts(line);
                    if (!amounts.isEmpty()) {
                        return amounts.get(amounts.size() - 1).doubleValue();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 0.0;
    }

    // ── Static detection helper ──────────────────────────────────────────────

    /**
     * Checks whether a PDF file is a BBVA Provincial bank statement.
     * Inspects the first page for identifying keywords.
     */
    public static boolean isProvincialBankStatement(File file) {
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String firstPage = stripper.getText(doc);

            boolean hasBankName = false;
            boolean hasStatementType = false;

            for (String keyword : BANK_KEYWORDS) {
                if (firstPage.contains(keyword)) {
                    if (keyword.contains("Provincial"))
                        hasBankName = true;
                    if (keyword.contains("ESTADO"))
                        hasStatementType = true;
                }
            }

            return hasBankName && hasStatementType;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Core processing logic ────────────────────────────────────────────────

    private List<Transaction> processLines(String[] lines, Transaction.Source source) {
        List<Transaction> transactions = new ArrayList<>();

        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();

            // Skip empty lines
            if (line.isEmpty()) {
                i++;
                continue;
            }

            // Skip header rows (column names repeated per page)
            if (isHeaderRow(line)) {
                i++;
                continue;
            }

            // Skip footer / page number / report date lines
            if (isFooterOrNoise(line)) {
                i++;
                continue;
            }

            // Skip bank identification / account holder blocks
            if (isBankMetadata(line)) {
                i++;
                continue;
            }

            // Attempt to parse as transaction line (starts with DD-MM-YYYY)
            Matcher txnMatcher = TXN_LINE.matcher(line);
            if (!txnMatcher.matches()) {
                i++;
                continue;
            }

            String dateStr = txnMatcher.group(1);
            String remainder = txnMatcher.group(2).trim();

            // Parse date
            LocalDate date;
            try {
                date = LocalDate.parse(dateStr, DATE_FMT);
            } catch (DateTimeParseException e) {
                i++;
                continue;
            }

            // ── Look-ahead: collect multi-line description ──
            // A continuation line does NOT start with a date and does NOT match
            // header/footer patterns.
            StringBuilder fullLine = new StringBuilder(remainder);
            int nextIdx = i + 1;
            while (nextIdx < lines.length) {
                String nextLine = lines[nextIdx].trim();
                if (nextLine.isEmpty())
                    break;
                if (TXN_LINE.matcher(nextLine).matches())
                    break;
                if (isHeaderRow(nextLine) || isFooterOrNoise(nextLine))
                    break;
                // This is a continuation line — append to description
                fullLine.append(" ").append(nextLine);
                nextIdx++;
            }

            i = nextIdx; // Advance past consumed lines

            // ── Parse the full transaction line ──
            Transaction txn = parseTransactionLine(date, fullLine.toString(), source);
            if (txn != null) {
                transactions.add(txn);
            }
        }

        return transactions;
    }

    /**
     * Parses a single transaction from the assembled line content (after the date).
     *
     * <p>
     * Expected structure (variable spacing):
     * {@code REF CONCEPTO [F.VALOR] CARGOS ABONOS [SALDO]}
     *
     * <p>
     * We use amount positions anchored from the right side of the line to
     * distinguish CARGOS, ABONOS, and SALDO.
     */
    private Transaction parseTransactionLine(LocalDate date, String lineContent,
            Transaction.Source source) {
        // Extract all amounts from the line
        List<AmountMatch> amountMatches = findAllAmounts(lineContent);

        if (amountMatches.isEmpty())
            return null;

        // ── Reference: first token (numeric) ──
        String reference = "S/N";
        Pattern refPattern = Pattern.compile("^(\\d{4,})\\s+");
        Matcher refMatcher = refPattern.matcher(lineContent);
        if (refMatcher.find()) {
            reference = refMatcher.group(1);
        }

        // ── Description: text between reference and first amount ──
        String description = "";
        if (!amountMatches.isEmpty()) {
            int firstAmtStart = amountMatches.get(0).start;
            // Adjust: firstAmtStart is relative to lineContent, but descriptionZone starts
            // later
            int refEnd = refMatcher.find(0) ? refMatcher.end() : 0;
            if (firstAmtStart > refEnd) {
                description = lineContent.substring(refEnd, firstAmtStart).trim();
            }
        }

        // Clean up description
        description = cleanDescription(description);

        // Skip "SALDO ANTERIOR" transactions — this is metadata, not a real transaction
        if (description.toUpperCase().contains("SALDO ANTERIOR")) {
            return null;
        }

        // ── Amount assignment logic ──
        // Provincial PDF has columns: ... CARGOS | ABONOS | SALDO
        // - If 3+ amounts: last = SALDO (ignored), second-to-last = ABONOS,
        // third-to-last = CARGOS
        // - If 2 amounts: could be CARGOS+SALDO or ABONOS+SALDO, or CARGOS+ABONOS
        // - If 1 amount: need to infer from position
        //
        // Heuristic: The last amount is usually SALDO. CARGOS and ABONOS occupy
        // the two columns before SALDO. Often only one of CARGOS/ABONOS has a value.

        BigDecimal withdrawal = BigDecimal.ZERO; // CARGOS
        BigDecimal deposit = BigDecimal.ZERO; // ABONOS

        int n = amountMatches.size();

        if (n >= 3) {
            // amounts[-3] = CARGOS, amounts[-2] = ABONOS, amounts[-1] = SALDO (ignored)
            // But either CARGOS or ABONOS will be the actual non-zero value
            BigDecimal val1 = amountMatches.get(n - 3).value;
            BigDecimal val2 = amountMatches.get(n - 2).value;
            // F.VALOR date may also produce a match, so check if val1 looks like a date
            // amount
            // Provincial statements: typically one of CARGOS/ABONOS is zero or missing
            withdrawal = val1;
            deposit = val2;
        } else if (n == 2) {
            // Two amounts: one is the transaction, one is SALDO
            // The amount that appears earlier in the line is the transaction amount
            BigDecimal val1 = amountMatches.get(0).value;

            // Determine: is it a CARGO or ABONO?
            // Use position heuristic: CARGOS comes before ABONOS in the line
            // and both come before SALDO
            // If only 2 amounts, last is likely SALDO
            // Need to determine if val1 is CARGO or ABONO based on description keywords
            String descUpper = description.toUpperCase();
            if (isWithdrawalKeyword(descUpper)) {
                withdrawal = val1;
            } else {
                deposit = val1;
            }
        } else if (n == 1) {
            // Single amount — use description keywords
            BigDecimal val = amountMatches.get(0).value;
            String descUpper = description.toUpperCase();
            if (isWithdrawalKeyword(descUpper)) {
                withdrawal = val;
            } else {
                deposit = val;
            }
        }

        if (withdrawal.compareTo(BigDecimal.ZERO) == 0 && deposit.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        return new Transaction(date, reference, description,
                deposit.doubleValue(), withdrawal.doubleValue(), source);
    }

    // ── Row classifiers ──────────────────────────────────────────────────────

    private boolean isHeaderRow(String line) {
        // Count how many column header keywords appear
        Matcher m = HEADER_ROW.matcher(line);
        int count = 0;
        while (m.find())
            count++;
        return count >= 2; // At least 2 column names → header row
    }

    private boolean isFooterOrNoise(String line) {
        return FOOTER_PATTERN.matcher(line.trim()).find();
    }

    private boolean isBankMetadata(String line) {
        String upper = line.toUpperCase().trim();
        // Bank name, account holder info, address lines, etc.
        return upper.startsWith("BBVA PROVINCIAL")
                || upper.startsWith("PROVINCIAL")
                || upper.contains("ESTADO DE CUENTA CORRIENTE")
                || upper.contains("DETALLE DE MOVIMIENTOS")
                || upper.contains("RIF:")
                || upper.contains("NIT:")
                || upper.contains("DIRECCION:")
                || upper.contains("DIRECCIÓN:")
                || upper.contains("CUENTA:")
                || upper.startsWith("CLIENTE:")
                || upper.startsWith("PERIODO:")
                || upper.startsWith("PERÍODO:")
                || upper.startsWith("MONEDA:");
    }

    private boolean isWithdrawalKeyword(String descUpper) {
        return descUpper.contains("CARGO")
                || descUpper.contains("CHEQUE")
                || descUpper.contains("N/D")
                || descUpper.contains("NOTA DE DEBITO")
                || descUpper.contains("NOTA DE DÉBITO")
                || descUpper.contains("PAGO")
                || descUpper.contains("RETIRO")
                || descUpper.contains("TRANSFERENCIA ENVIADA")
                || descUpper.contains("DOMICILIACION")
                || descUpper.contains("DOMICILIACIÓN")
                || descUpper.contains("COMISION")
                || descUpper.contains("COMISIÓN")
                || descUpper.contains("I.G.T.F")
                || descUpper.contains("IGTF");
    }

    // ── Amount extraction ────────────────────────────────────────────────────

    private static class AmountMatch {
        final BigDecimal value;
        final int start;

        AmountMatch(BigDecimal value, int start) {
            this.value = value;
            this.start = start;
        }
    }

    /**
     * Finds all monetary amounts in a string and returns them with their positions.
     */
    private List<AmountMatch> findAllAmounts(String text) {
        List<AmountMatch> matches = new ArrayList<>();
        Matcher m = AMOUNT_PATTERN.matcher(text);
        while (m.find()) {
            String raw = m.group(1);
            BigDecimal parsed = parseProvincialAmount(raw);
            if (parsed != null && parsed.compareTo(BigDecimal.ZERO) > 0) {
                matches.add(new AmountMatch(parsed, m.start()));
            }
        }
        return matches;
    }

    /**
     * Extracts raw amounts (without position tracking) for saldo inicial detection.
     */
    private List<BigDecimal> extractAmounts(String text) {
        List<BigDecimal> amounts = new ArrayList<>();
        Matcher m = AMOUNT_PATTERN.matcher(text);
        while (m.find()) {
            BigDecimal parsed = parseProvincialAmount(m.group(1));
            if (parsed != null && parsed.compareTo(BigDecimal.ZERO) > 0) {
                amounts.add(parsed);
            }
        }
        return amounts;
    }

    /**
     * Parses a Provincial-format amount string:
     * <ol>
     * <li>Strip thousand-separator commas</li>
     * <li>Replace OCR dash-decimal ({@code -}) with actual dot</li>
     * <li>Parse as {@link BigDecimal}</li>
     * </ol>
     * Examples: {@code "3,087,990-20"} → {@code 3087990.20},
     * {@code "12,365.00"} → {@code 12365.00}
     */
    private BigDecimal parseProvincialAmount(String raw) {
        if (raw == null || raw.trim().isEmpty())
            return null;
        try {
            String cleaned = raw.trim();

            // Remove thousand-separator commas
            cleaned = cleaned.replace(",", "");

            // Handle OCR artifact: dash used as decimal separator
            // Pattern: digits-digits (e.g. "3087990-20")
            // But NOT if it looks like a date (handled separately)
            if (cleaned.contains("-")) {
                // Replace the LAST dash with a dot (decimal separator)
                int lastDash = cleaned.lastIndexOf('-');
                if (lastDash > 0 && lastDash < cleaned.length() - 1) {
                    String afterDash = cleaned.substring(lastDash + 1);
                    // Only treat as decimal if after-dash part is exactly 2 digits
                    if (afterDash.length() == 2 && afterDash.matches("\\d{2}")) {
                        cleaned = cleaned.substring(0, lastDash) + "." + afterDash;
                    }
                }
            }

            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Description cleanup ──────────────────────────────────────────────────

    /**
     * Cleans a multi-line description:
     * - Remove embedded dates (F.VALOR repetitions)
     * - Collapse whitespace
     */
    private String cleanDescription(String desc) {
        if (desc == null)
            return "";
        // Remove any inline dates (DD-MM-YYYY) that leaked from F.VALOR column
        String cleaned = desc.replaceAll("\\d{2}-\\d{2}-\\d{4}", "").trim();
        // Collapse multiple spaces
        cleaned = cleaned.replaceAll("\\s{2,}", " ").trim();
        return cleaned;
    }

    // ── PDF text extraction ──────────────────────────────────────────────────

    private String extractText(File file) throws Exception {
        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true); // Critical for tabular PDFs
            return stripper.getText(document);
        }
    }
}
