package com.bankreconciliation.parser;

import com.bankreconciliation.model.Transaction;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.swing.*;
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
 * Procesador de Estados de Cuenta (PDF) del Banco Provincial (BBVA).
 * <p>
 * <b>Estructura del PDF:</b>
 * <ul>
 * <li>Cabecera del banco y datos del titular (primeras líneas)</li>
 * <li>Línea "SALDO ANTERIOR" con el balance inicial</li>
 * <li>Transacciones:
 * {@code DD-MM-YYYY REF CONCEPTO DD-MM-YYYY MONTO SALDO}</li>
 * <li>Ruido inter-página: {@code BGPRX1}, fecha de impresión, cabeceras
 * repetidas</li>
 * </ul>
 * <p>
 * <b>Método Delta SALDO:</b> Cada línea tiene exactamente 2 montos: el valor de
 * la
 * transacción y el SALDO acumulado. No hay columnas separadas para CARGOS y
 * ABONOS.
 * Se compara el SALDO actual con el anterior para determinar si es depósito o
 * retiro:
 * <ul>
 * <li>SALDO actual &gt; SALDO anterior → <b>ABONO</b> (depósito)</li>
 * <li>SALDO actual &lt; SALDO anterior → <b>CARGO</b> (retiro)</li>
 * </ul>
 * <p>
 * <b>Manejo de Errores:</b> Errores de parsing disparan {@link JOptionPane}
 * modal
 * con detalle de la línea y opción de Continuar/Abortar.
 */
public class ProvincialBankStatementProcessor implements FileParser {

    // ─── Formatos y Patrones ─────────────────────────────────────────────────
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    /**
     * Línea de transacción: comienza con DD-MM-YYYY, captura (1) fecha y (2) resto.
     */
    private static final Pattern TXN_LINE = Pattern.compile(
            "^(\\d{2}-\\d{2}-\\d{4})\\s+(.+)$");

    /**
     * Monto monetario: soporta formatos como 3,087,990.20 12,365.00 37.09 0.67
     * También soporta OCR artifact con guion: 3,087,990-20
     */
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(\\d{1,3}(?:,\\d{3})*(?:[.\\-]\\d{2}))");

    /** Patrón para ruido: pie de página, número de página, fecha impresión. */
    private static final Pattern FOOTER_PATTERN = Pattern.compile(
            "^\\d{2}/\\d{2}/\\d{4}\\s+\\d{2}[.]\\d{2}"
                    + "|^Pág(?:ina)?[.:]?\\s*\\d+"
                    + "|^Página\\s+\\d+"
                    + "|^\\s*\\d+\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** Cabecera de columnas repetida en cada página. */
    private static final Pattern HEADER_ROW = Pattern.compile(
            "F\\.?\\s*OPER|REF\\.?|CONCEPTO|F\\.?\\s*VALOR|CARGOS|ABONOS|SALDO",
            Pattern.CASE_INSENSITIVE);

    /** Identificador de sistema que aparece como separador de páginas. */
    private static final Pattern SYSTEM_CODE = Pattern.compile("^[A-Z]{2,6}[A-Z0-9]{0,4}$");

    private static final String ERROR_DIALOG_TITLE = "Error en Procesamiento del Estado de Cuenta";

    // ═══════════════════════════════════════════════════════════════════════════
    // Interfaz FileParser
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public List<Transaction> parse(File file, Transaction.Source source) throws Exception {
        System.out.println("Iniciando procesamiento de Estado de Cuenta Provincial: " + file.getName());

        String fullText;
        try {
            fullText = extractText(file);
        } catch (Exception ex) {
            showCriticalError(0, file.getAbsolutePath(), ex,
                    "No se pudo leer el archivo PDF. Verifique que no esté corrupto o protegido.");
            throw ex;
        }

        String[] allLines = fullText.split("\\r?\\n");
        return processLines(allLines, source);
    }

    @Override
    public double extractSaldoInicial(File file) {
        try {
            String text = extractText(file);
            String[] lines = text.split("\\r?\\n");

            for (String line : lines) {
                if (line.toUpperCase().contains("SALDO ANTERIOR")) {
                    List<BigDecimal> amounts = extractAmountsFromText(line);
                    if (!amounts.isEmpty()) {
                        return amounts.get(amounts.size() - 1).doubleValue();
                    }
                }
            }

            for (String line : lines) {
                String upper = line.toUpperCase().trim();
                if (upper.contains("SALDO INICIAL") || upper.contains("SALDO AL")) {
                    List<BigDecimal> amounts = extractAmountsFromText(line);
                    if (!amounts.isEmpty()) {
                        return amounts.get(amounts.size() - 1).doubleValue();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 0.0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Detección de formato
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Verifica si un archivo PDF es un Estado de Cuenta del Banco Provincial.
     * Busca la combinación de "Provincial" (en cualquier contexto: BBVA, Linea,
     * etc.)
     * junto con "ESTADO DE CUENTA" en la primera página.
     * También acepta el código de banco 0108- como identificador secundario.
     */
    public static boolean isProvincialBankStatement(File file) {
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String firstPage = stripper.getText(doc);
            String upper = firstPage.toUpperCase();

            boolean hasProvincial = upper.contains("PROVINCIAL");
            boolean hasEstadoCuenta = upper.contains("ESTADO DE CUENTA");
            // Código de banco Provincial: 0108
            boolean hasBankCode = firstPage.contains("0108-");

            return hasEstadoCuenta && (hasProvincial || hasBankCode);
        } catch (Exception e) {
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Motor de Procesamiento — Método Delta SALDO (2 pasadas)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Registro intermedio para la primera pasada: guarda los datos crudos
     * de cada transacción SIN clasificar CARGO/ABONO aún.
     */
    private record RawTransaction(
            LocalDate date,
            String reference,
            String description,
            BigDecimal transactionAmount,
            BigDecimal saldo,
            int lineNumber,
            String rawLine) {
    }

    /**
     * Proceso principal en 2 pasadas:
     * <ol>
     * <li><b>Pasada 1:</b> Extrae todas las transacciones con su SALDO y monto
     * bruto</li>
     * <li><b>Pasada 2:</b> Compara SALDOs consecutivos para clasificar CARGO vs
     * ABONO</li>
     * </ol>
     */
    private List<Transaction> processLines(String[] lines, Transaction.Source source) throws Exception {
        // ═════════════════════════════════════════════════════════════════════
        // PASADA 1: Extraer transacciones crudas + SALDO ANTERIOR
        // ═════════════════════════════════════════════════════════════════════
        List<RawTransaction> rawTransactions = new ArrayList<>();
        BigDecimal saldoAnterior = null;

        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();

            // Saltar líneas vacías
            if (line.isEmpty()) {
                i++;
                continue;
            }

            // Saltar cabeceras de columnas (repetidas en cada página)
            if (isHeaderRow(line)) {
                i++;
                continue;
            }

            // Saltar pie de página / ruido
            if (isFooterOrNoise(line)) {
                i++;
                continue;
            }

            // Saltar metadatos del banco
            if (isBankMetadata(line)) {
                i++;
                continue;
            }

            // Saltar códigos de sistema (ej. "BGPRX1")
            if (SYSTEM_CODE.matcher(line).matches()) {
                i++;
                continue;
            }

            // ── SALDO ANTERIOR ──
            if (line.toUpperCase().contains("SALDO ANTERIOR")) {
                List<BigDecimal> amounts = extractAmountsFromText(line);
                if (!amounts.isEmpty()) {
                    saldoAnterior = amounts.get(amounts.size() - 1);
                    System.out.println("Saldo Anterior detectado: " + saldoAnterior);
                }
                i++;
                continue;
            }

            // ── Intentar parsear como línea de transacción (DD-MM-YYYY ...) ──
            Matcher txnMatcher = TXN_LINE.matcher(line);
            if (!txnMatcher.matches()) {
                i++;
                continue;
            }

            String dateStr = txnMatcher.group(1);
            String remainder = txnMatcher.group(2).trim();

            // Look-ahead: juntar líneas de continuación (descripción multilínea)
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
                if (isBankMetadata(nextLine))
                    break;
                if (SYSTEM_CODE.matcher(nextLine).matches())
                    break;
                if (nextLine.toUpperCase().contains("SALDO ANTERIOR"))
                    break;
                fullLine.append(" ").append(nextLine);
                nextIdx++;
            }
            i = nextIdx;

            // ── TRY-CATCH GRANULAR ──
            try {
                LocalDate date = LocalDate.parse(dateStr, DATE_FMT);
                String assembledLine = fullLine.toString();

                // Extraer referencia (primer token numérico de 4+ dígitos)
                String reference = "S/N";
                Matcher refMatcher = Pattern.compile("^(\\d{4,})\\s+").matcher(assembledLine);
                if (refMatcher.find()) {
                    reference = refMatcher.group(1);
                }

                // Extraer todos los montos de la línea
                List<AmountMatch> amountMatches = findAllAmounts(assembledLine);

                if (amountMatches.size() < 2) {
                    // Necesitamos al menos 2 montos: transacción + saldo
                    // Con 1 solo monto no podemos determinar nada confiablemente
                    continue;
                }

                // Los últimos 2 montos son: transacción y SALDO
                BigDecimal txnAmount = amountMatches.get(amountMatches.size() - 2).value;
                BigDecimal saldo = amountMatches.get(amountMatches.size() - 1).value;

                // Descripción: texto entre la referencia y el primer monto
                int refEnd = refMatcher.find(0) ? refMatcher.end() : 0;
                int firstAmtStart = amountMatches.get(0).start;
                String description = "";
                if (firstAmtStart > refEnd) {
                    description = assembledLine.substring(refEnd, firstAmtStart).trim();
                }
                description = cleanDescription(description);

                // Saltar "SALDO ANTERIOR" como transacción
                if (description.toUpperCase().contains("SALDO ANTERIOR")) {
                    if (saldoAnterior == null) {
                        saldoAnterior = saldo;
                    }
                    continue;
                }

                rawTransactions.add(new RawTransaction(
                        date, reference, description, txnAmount, saldo,
                        i, line));

            } catch (DateTimeParseException dtpe) {
                boolean continuar = showCriticalError(i, line, dtpe,
                        "La fecha '" + dateStr + "' no tiene formato DD-MM-YYYY válido.");
                if (!continuar)
                    return new ArrayList<>();
            } catch (Exception ex) {
                boolean continuar = showCriticalError(i, line, ex,
                        "Error inesperado al procesar esta línea de transacción.");
                if (!continuar)
                    return new ArrayList<>();
            }
        }

        // ═════════════════════════════════════════════════════════════════════
        // PASADA 2: Clasificar CARGO vs ABONO usando Delta SALDO
        // ═════════════════════════════════════════════════════════════════════
        List<Transaction> transactions = new ArrayList<>();
        BigDecimal previousSaldo = saldoAnterior;

        for (RawTransaction raw : rawTransactions) {
            try {
                double deposit = 0.0;
                double withdrawal = 0.0;

                if (previousSaldo != null) {
                    // Delta = SALDO actual - SALDO anterior
                    // Positivo = ABONO (depósito), Negativo = CARGO (retiro)
                    int delta = raw.saldo.compareTo(previousSaldo);

                    if (delta > 0) {
                        // El saldo subió → ABONO (depósito)
                        deposit = raw.transactionAmount.doubleValue();
                    } else if (delta < 0) {
                        // El saldo bajó → CARGO (retiro)
                        withdrawal = raw.transactionAmount.doubleValue();
                    }
                    // delta == 0: raro, pero posible si monto es 0 → se ignora
                } else {
                    // Sin saldo anterior: usar heurística por descripción como fallback
                    String descUpper = raw.description.toUpperCase();
                    if (isWithdrawalKeyword(descUpper)) {
                        withdrawal = raw.transactionAmount.doubleValue();
                    } else {
                        deposit = raw.transactionAmount.doubleValue();
                    }
                }

                if (deposit == 0.0 && withdrawal == 0.0) {
                    previousSaldo = raw.saldo;
                    continue;
                }

                transactions.add(new Transaction(
                        raw.date, raw.reference, raw.description,
                        deposit, withdrawal, source));

                previousSaldo = raw.saldo;

            } catch (Exception ex) {
                boolean continuar = showCriticalError(raw.lineNumber, raw.rawLine, ex,
                        "Error al clasificar la transacción como CARGO o ABONO.");
                if (!continuar)
                    return transactions;
                previousSaldo = raw.saldo;
            }
        }

        System.out.println("Procesamiento del Estado de Cuenta finalizado. "
                + "Transacciones: " + transactions.size());
        return transactions;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Clasificadores de Fila
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean isHeaderRow(String line) {
        Matcher m = HEADER_ROW.matcher(line);
        int count = 0;
        while (m.find())
            count++;
        return count >= 2;
    }

    private boolean isFooterOrNoise(String line) {
        return FOOTER_PATTERN.matcher(line.trim()).find();
    }

    private boolean isBankMetadata(String line) {
        String upper = line.toUpperCase().trim();
        return upper.startsWith("BBVA PROVINCIAL")
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
                || upper.startsWith("MONEDA:")
                || upper.startsWith("TITULAR:")
                || upper.startsWith("NRO. DE CUENTA:")
                || upper.contains("A PARTIR DEL")
                || upper.contains("EXPRESIÓN MONETARIA")
                || upper.contains("EXPRESION MONETARIA")
                || upper.contains("PUEDE VALIDAR")
                || upper.contains("LINEA PROVINCIAL")
                || upper.contains("CONFORMACION:");
    }

    /**
     * Heurística de fallback para determinar si una transacción es retiro,
     * usada SOLO cuando no hay SALDO anterior disponible.
     */
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
                || descUpper.contains("COMIS.")
                || descUpper.contains("I.G.T.F")
                || descUpper.contains("IGTF")
                || descUpper.contains("TRASP.")
                || descUpper.contains("COM TRF")
                || descUpper.contains("CR.I/OB");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Extracción de Montos
    // ═══════════════════════════════════════════════════════════════════════════

    private static class AmountMatch {
        final BigDecimal value;
        final int start;

        AmountMatch(BigDecimal value, int start) {
            this.value = value;
            this.start = start;
        }
    }

    /**
     * Encuentra todos los montos monetarios en un texto, con sus posiciones.
     * Ignora valores que parecen ser años (ej. 2025).
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
     * Extrae montos sin tracking de posición, para la detección de SALDO ANTERIOR.
     */
    private List<BigDecimal> extractAmountsFromText(String text) {
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
     * Parsea un monto del formato Provincial:
     * <ol>
     * <li>Elimina comas de miles</li>
     * <li>Reemplaza guión decimal OCR ({@code 3087990-20} →
     * {@code 3087990.20})</li>
     * <li>Parsea como {@link BigDecimal}</li>
     * </ol>
     */
    private BigDecimal parseProvincialAmount(String raw) {
        if (raw == null || raw.trim().isEmpty())
            return null;
        try {
            String cleaned = raw.trim().replace(",", "");

            // OCR artifact: guión como separador decimal
            if (cleaned.contains("-")) {
                int lastDash = cleaned.lastIndexOf('-');
                if (lastDash > 0 && lastDash < cleaned.length() - 1) {
                    String afterDash = cleaned.substring(lastDash + 1);
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

    // ═══════════════════════════════════════════════════════════════════════════
    // Limpieza de Descripción
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Limpia la descripción: elimina fechas inline (F.VALOR) y colapsa espacios.
     */
    private String cleanDescription(String desc) {
        if (desc == null)
            return "";
        String cleaned = desc.replaceAll("\\d{2}-\\d{2}-\\d{4}", "").trim();
        cleaned = cleaned.replaceAll("\\s{2,}", " ").trim();
        return cleaned;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Extracción de Texto PDF
    // ═══════════════════════════════════════════════════════════════════════════

    private String extractText(File file) throws Exception {
        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true); // Crítico para PDFs tabulares
            return stripper.getText(document);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Diálogo de Error Crítico (JOptionPane)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Muestra un diálogo modal de error para que el usuario reconozca el problema.
     * 
     * @return {@code true} si desea continuar, {@code false} para abortar
     */
    private boolean showCriticalError(int lineNumber, String rawLineContent,
            Exception exception, String userHint) {
        System.err.println("ERROR en línea " + lineNumber + ": " + exception.getMessage());
        exception.printStackTrace();

        String displayLine = rawLineContent;
        if (displayLine != null && displayLine.length() > 200) {
            displayLine = displayLine.substring(0, 200) + "...";
        }

        String message = String.format(
                """
                        ══════════════════════════════════════════
                             ERROR EN ESTADO DE CUENTA PDF
                        ══════════════════════════════════════════

                        ► Línea Nº: %d
                        ► Contenido de la línea:
                          %s

                        ► Causa Técnica:
                          Tipo: %s
                          Detalle: %s

                        ► Acción Requerida:
                          %s

                        ══════════════════════════════════════════
                        ¿Desea continuar procesando las líneas restantes?
                        (Seleccione "No" para abortar el procesamiento)
                        """,
                lineNumber,
                displayLine != null ? displayLine : "(no disponible)",
                exception.getClass().getSimpleName(),
                exception.getMessage() != null ? exception.getMessage() : "(sin mensaje)",
                userHint);

        int result = JOptionPane.showConfirmDialog(
                null,
                message,
                ERROR_DIALOG_TITLE,
                JOptionPane.YES_NO_OPTION,
                JOptionPane.ERROR_MESSAGE);

        return result == JOptionPane.YES_OPTION;
    }
}
