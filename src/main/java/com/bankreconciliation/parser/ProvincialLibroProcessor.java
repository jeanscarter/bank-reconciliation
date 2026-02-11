package com.bankreconciliation.parser;

import com.bankreconciliation.model.Transaction;

import org.apache.poi.ss.usermodel.*;

import javax.swing.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Procesador para "Libro Contable" del Banco Provincial.
 * <p>
 * Soporta tanto archivos CSV como XLS/XLSX con estructura jerárquica:
 * <ul>
 * <li><b>Fila de Fecha (Control):</b> Primera columna contiene un "Excel Serial
 * Date"
 * (ej. 45992.0). Define la fecha para todas las transacciones
 * subsiguientes.</li>
 * <li><b>Fila de Transacción:</b> Comienza con un número de secuencia (ej.
 * 0000017835).
 * Contiene Referencia, Descripción, Debe y Haber.</li>
 * <li><b>Filas de Ruido:</b> Cabeceras, sub-totales, y filas vacías que se
 * ignoran.</li>
 * </ul>
 * <p>
 * <b>Manejo de Errores:</b> Todos los errores de procesamiento disparan una
 * ventana modal
 * {@link JOptionPane} que detalla la línea, contenido crudo y causa técnica.
 * El usuario puede elegir continuar o abortar el procesamiento.
 */
public class ProvincialLibroProcessor implements FileParser {

    // ─── Constantes ──────────────────────────────────────────────────────────────
    /** Epoch de Excel: 30 de diciembre de 1899 (convención estándar). */
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    /**
     * Patrón para detectar "Excel Serial Date" en columna 0 (ej. "45992.0" o
     * "45992").
     */
    private static final Pattern SERIAL_DATE_PATTERN = Pattern.compile("^\\d{4,6}(\\.\\d+)?$");

    /**
     * Patrón para detectar fila de transacción: secuencia numérica en columna 0.
     */
    private static final Pattern TRANSACTION_SEQ_PATTERN = Pattern.compile("^\\d+$");

    // ─── Índices de Columna ──────────────────────────────────────────────────────
    private static final int COL_SERIAL_DATE = 0;
    private static final int COL_REFERENCIA = 4;
    private static final int COL_DESCRIPCION = 6;
    private static final int COL_DEBE = 8;
    private static final int COL_HABER = 9;

    // ─── Título del diálogo de error ─────────────────────────────────────────────
    private static final String ERROR_DIALOG_TITLE = "Error Crítico en Procesamiento de Archivo";

    // ═══════════════════════════════════════════════════════════════════════════════
    // Interfaz FileParser
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Punto de entrada principal. Detecta el tipo de archivo (CSV vs XLS) y
     * delega la lectura al método apropiado, luego procesa todas las filas
     * con la misma lógica jerárquica.
     */
    @Override
    public List<Transaction> parse(File file, Transaction.Source source) throws Exception {
        System.out.println("Iniciando procesamiento de archivo Provincial: " + file.getName());

        List<String[]> rows;
        try {
            if (isExcelFile(file)) {
                rows = readRowsFromExcel(file);
            } else {
                rows = readRowsFromCsv(file);
            }
        } catch (Exception ioEx) {
            // ── Error de I/O al leer el archivo: diálogo modal inmediato ──
            showCriticalError(0, file.getAbsolutePath(), ioEx,
                    "No se pudo leer el archivo. Verifique que existe, no está bloqueado y tiene permisos de lectura.");
            throw ioEx;
        }

        return processRows(rows, source);
    }

    @Override
    public double extractSaldoInicial(File file) {
        return 0.0;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Detección de formato
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Heurística para detectar si un archivo es del formato Provincial (Libro
     * Contable).
     * Soporta tanto CSV como XLS/XLSX.
     */
    public static boolean isProvincialFormat(File file) {
        String name = file.getName().toLowerCase();

        if (name.endsWith(".xls") || name.endsWith(".xlsx")) {
            return isProvincialExcelFormat(file);
        }
        return isProvincialCsvFormat(file);
    }

    /**
     * Detecta formato Provincial en archivos XLS/XLSX usando Apache POI.
     * Busca en la primera fila: col 0 = "Número" y col 4 = "Nro. Doc."
     */
    private static boolean isProvincialExcelFormat(File file) {
        try (FileInputStream fis = new FileInputStream(file);
                Workbook workbook = WorkbookFactory.create(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            // Revisar las primeras 5 filas buscando la cabecera provincial
            for (int i = 0; i <= Math.min(5, sheet.getLastRowNum()); i++) {
                Row row = sheet.getRow(i);
                if (row == null)
                    continue;

                String col0 = getCellAsString(row.getCell(0));
                String col4 = getCellAsString(row.getCell(4));

                if (col0.contains("Número") && col4.contains("Nro. Doc")) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Si no se puede abrir, no es formato Provincial
        }
        return false;
    }

    /**
     * Detecta formato Provincial en archivos CSV (lógica original).
     */
    private static boolean isProvincialCsvFormat(File file) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.ISO_8859_1))) {
            String line;
            int checkLimit = 15;
            while ((line = br.readLine()) != null && checkLimit-- > 0) {
                if (line.contains("Número") && line.contains("Nro. Doc."))
                    return true;
                if (line.contains("4599") && line.contains(",,,"))
                    return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Lectores de Archivo (CSV y XLS)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Lee un archivo XLS/XLSX y convierte cada fila a String[], unificando
     * la representación con el lector CSV.
     */
    private List<String[]> readRowsFromExcel(File file) throws Exception {
        List<String[]> result = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(file);
                Workbook workbook = WorkbookFactory.create(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    result.add(new String[0]);
                    continue;
                }

                int lastCol = row.getLastCellNum();
                if (lastCol < 0)
                    lastCol = 0;
                String[] cells = new String[lastCol];
                for (int c = 0; c < lastCol; c++) {
                    cells[c] = getCellAsString(row.getCell(c));
                }
                result.add(cells);
            }
        }
        return result;
    }

    /**
     * Lee un archivo CSV y convierte cada línea a String[] usando el parser CSV.
     */
    private List<String[]> readRowsFromCsv(File file) throws IOException {
        List<String> lines = readAllLines(file);
        List<String[]> result = new ArrayList<>();
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                result.add(new String[0]);
            } else {
                result.add(splitCSV(line));
            }
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Motor Principal de Procesamiento
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Procesa todas las filas del archivo con la lógica jerárquica:
     * <ol>
     * <li>Detecta filas de fecha y actualiza {@code currentDate}.</li>
     * <li>Filtra filas de ruido (cabeceras, sub-totales, vacías, *ANULADO*).</li>
     * <li>Extrae datos de transacción usando {@code currentDate} como fecha.</li>
     * </ol>
     * Cada fila se procesa dentro de un try-catch granular. Si ocurre un error,
     * se muestra un {@link JOptionPane} modal y el usuario decide si continuar.
     */
    private List<Transaction> processRows(List<String[]> rows, Transaction.Source source) throws Exception {
        List<Transaction> transactions = new ArrayList<>();
        // ── FECHA JERÁRQUICA: inicia en null, se actualiza al encontrar fila de fecha
        // ──
        LocalDate currentDate = null;
        int lineNumber = 0;

        for (String[] cells : rows) {
            lineNumber++;

            // Fila vacía: ignorar
            if (cells.length == 0)
                continue;

            // Reconstruir representación textual de la fila para mensajes de error
            String rawLine = String.join(" | ", cells);

            // ── TRY-CATCH GRANULAR POR LÍNEA ──────────────────────────────
            try {
                // ─────────────────────────────────────────────────────────────
                // 1. DETECCIÓN DE FECHA JERÁRQUICA (Fila de Control)
                // Si col 0 es un Excel Serial Date (ej. 45992.0), se convierte
                // a LocalDate y se establece como currentDate para todas las
                // transacciones subsecuentes.
                // ─────────────────────────────────────────────────────────────
                if (isSerialDateRow(cells)) {
                    currentDate = parseSerialDate(cells[COL_SERIAL_DATE]);
                    continue;
                }

                // ─────────────────────────────────────────────────────────────
                // 2. FILTROS DE RUIDO
                // Se ignoran: cabeceras, sub-totales, y filas con pocas columnas.
                // ─────────────────────────────────────────────────────────────
                String col0 = cleanField(cells[0]);
                if (col0.startsWith("Número") || col0.startsWith("Numero"))
                    continue;
                if (rawLine.contains("Sub-Totales") || rawLine.contains("Sub-Total"))
                    continue;
                if (cells.length <= COL_HABER)
                    continue;

                // ─────────────────────────────────────────────────────────────
                // 3. DETECCIÓN DE FILA DE TRANSACCIÓN
                // Col 0 debe ser un número de secuencia (regex ^\d+$)
                // y currentDate no debe ser null.
                // ─────────────────────────────────────────────────────────────
                if (!TRANSACTION_SEQ_PATTERN.matcher(col0).matches())
                    continue;

                // 3a. Extracción y limpieza de campos
                String referencia = cleanField(cells[COL_REFERENCIA]);
                String descripcion = cleanField(cells[COL_DESCRIPCION]);

                // Excluir transacciones anuladas
                if (descripcion.contains("*ANULADO*"))
                    continue;
                // Excluir filas sin datos útiles
                if (referencia.isEmpty() && descripcion.isEmpty())
                    continue;

                // ─────────────────────────────────────────────────────────────
                // 4. VALIDACIÓN DE MONTOS
                // Se usa BigDecimal como paso intermedio para robustez,
                // luego se convierte a double (compatible con Transaction).
                // Si la celda está vacía/nula, se trata como 0.00.
                // ─────────────────────────────────────────────────────────────
                double debe = parseAmountSafe(cells[COL_DEBE]);
                double haber = parseAmountSafe(cells[COL_HABER]);

                // No registrar transacciones sin movimiento
                if (debe == 0 && haber == 0)
                    continue;

                // ─────────────────────────────────────────────────────────────
                // 5. VALIDACIÓN DE FECHA
                // Si no se ha detectado ninguna fila de fecha antes de esta
                // transacción, se dispara el diálogo de error.
                // ─────────────────────────────────────────────────────────────
                if (currentDate == null) {
                    throw new IllegalStateException(
                            "Transacción encontrada en línea " + lineNumber
                                    + " antes de detectar una fila de fecha válida. "
                                    + "Verifique que el archivo contiene filas de fecha (Excel Serial Date) "
                                    + "antes de las transacciones.");
                }

                // ── Crear y agregar la transacción ──
                transactions.add(new Transaction(
                        currentDate,
                        referencia,
                        descripcion,
                        debe,
                        haber,
                        source));

            } catch (Exception ex) {
                // ═══════════════════════════════════════════════════════════════
                // MANEJO DE ERRORES CRÍTICO — VENTANA MODAL JOptionPane
                // Cualquier error (NumberFormatException, IndexOutOfBounds,
                // IllegalStateException, etc.) dispara un diálogo modal que
                // obliga al usuario a reconocer el problema.
                // ═══════════════════════════════════════════════════════════════
                boolean continuar = showCriticalError(lineNumber, rawLine, ex,
                        "Verifique el formato de la celda en la línea indicada.");

                if (!continuar) {
                    System.err.println("Procesamiento abortado por el usuario en línea " + lineNumber);
                    return transactions;
                }
            }
        }

        System.out.println("Procesamiento finalizado. Total transacciones: " + transactions.size());
        return transactions;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Métodos de Detección y Parsing
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Detecta si una fila es una "Fila de Fecha" (Control).
     * Criterio: col 0 coincide con el patrón de fecha serial y las columnas 1-4
     * están vacías.
     */
    private boolean isSerialDateRow(String[] cells) {
        if (cells.length == 0)
            return false;
        String col0 = cleanField(cells[0]);
        if (!SERIAL_DATE_PATTERN.matcher(col0).matches())
            return false;

        // Las filas de fecha típicamente tienen las columnas 1-4 vacías
        int checkUntil = Math.min(cells.length, 5);
        for (int i = 1; i < checkUntil; i++) {
            if (!cleanField(cells[i]).isEmpty())
                return false;
        }
        return true;
    }

    /**
     * Convierte un "Excel Serial Date" (ej. "45992.0") a {@link LocalDate}.
     * Fórmula: EXCEL_EPOCH (1899-12-30) + serialValue días.
     */
    private LocalDate parseSerialDate(String raw) {
        double serialVal = Double.parseDouble(cleanField(raw));
        return EXCEL_EPOCH.plusDays((long) serialVal);
    }

    /**
     * Parsea un monto de forma segura usando {@link BigDecimal} como paso
     * intermedio.
     * <ul>
     * <li>Elimina comillas y espacios.</li>
     * <li>Elimina comas de miles (ej. "1,234.56" → "1234.56").</li>
     * <li>Si el campo está vacío o nulo, retorna 0.0.</li>
     * </ul>
     */
    private double parseAmountSafe(String raw) throws NumberFormatException {
        if (raw == null)
            return 0.0;

        String clean = raw.replaceAll("\"", "").trim();
        clean = clean.replace(",", "");

        if (clean.isEmpty())
            return 0.0;

        return new BigDecimal(clean).doubleValue();
    }

    /** Limpia un campo: elimina comillas externas y espacios. */
    private String cleanField(String raw) {
        if (raw == null)
            return "";
        return raw.trim().replaceAll("^\"|\"$", "").trim();
    }

    /** Verifica si el archivo es un Excel (XLS o XLSX). */
    private static boolean isExcelFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".xls") || name.endsWith(".xlsx");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Utilidades: CSV Parser, lectura de archivo, conversión de celda POI
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Parser CSV que respeta comillas pero NO las incluye en el resultado. */
    private String[] splitCSV(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (char c : line.toCharArray()) {
            if (c == '"')
                inQuotes = !inQuotes;
            else if (c == ',' && !inQuotes) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString());
        return result.toArray(new String[0]);
    }

    /**
     * Lee todas las líneas del archivo CSV, intentando UTF-8 y cayendo a
     * ISO-8859-1.
     */
    private List<String> readAllLines(File file) throws IOException {
        try {
            return java.nio.file.Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return java.nio.file.Files.readAllLines(file.toPath(), StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * Convierte una celda POI a String preservando valores numéricos crudos.
     * <p>
     * IMPORTANTE: NO se usa {@code DateUtil.isCellDateFormatted()} porque las
     * celdas de fecha serial (ej. 45992.0) son las "Filas de Control" jerárquicas
     * que debemos detectar como números, no como fechas formateadas de Java.
     */
    private static String getCellAsString(Cell cell) {
        if (cell == null)
            return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                // Siempre devolver el valor numérico crudo para que el detector
                // de fecha serial (isSerialDateRow) reconozca "45992", etc.
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                }
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield String.valueOf(cell.getNumericCellValue());
                } catch (Exception e) {
                    try {
                        yield cell.getStringCellValue();
                    } catch (Exception e2) {
                        yield "";
                    }
                }
            }
            default -> "";
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Diálogo de Error Crítico (JOptionPane)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Muestra una ventana modal de error para que el usuario reconozca el problema.
     * Incluye: número de línea, contenido crudo, tipo de excepción, y una
     * instrucción.
     * El usuario elige "Sí" para continuar o "No" para abortar.
     *
     * @return {@code true} si el usuario desea continuar, {@code false} para
     *         abortar
     */
    private boolean showCriticalError(int lineNumber, String rawLineContent,
            Exception exception, String userHint) {
        System.err.println("ERROR CRÍTICO en línea " + lineNumber + ": " + exception.getMessage());
        exception.printStackTrace();

        // Truncar línea si es demasiado larga para el diálogo
        String displayLine = rawLineContent;
        if (displayLine != null && displayLine.length() > 200) {
            displayLine = displayLine.substring(0, 200) + "...";
        }

        String message = String.format(
                """
                        ══════════════════════════════════════════
                                 ERROR EN PROCESAMIENTO
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