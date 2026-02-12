package com.bankreconciliation.parser;

import java.io.File;

/**
 * Routes file to the appropriate parser based on extension.
 */
public class ParserFactory {

    public static FileParser getParser(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            // Auto-detect Banco Provincial Libro Contable format (hierarchical)
            if (ProvincialLibroProcessor.isProvincialFormat(file)) {
                return new ProvincialLibroProcessor();
            }
            // Auto-detect Banesco Libro
            if (BanescoLibroProcessor.isBanescoFormat(file)) {
                return new BanescoLibroProcessor();
            }
            return new XlsParser();
        } else if (name.endsWith(".csv")) {
            // Auto-detect Banco Provincial Libro Contable format
            if (ProvincialLibroProcessor.isProvincialFormat(file)) {
                return new ProvincialLibroProcessor();
            }
            return new CsvParser();
        } else if (name.endsWith(".pdf")) {
            // Auto-detect BBVA Provincial bank statement PDF
            if (ProvincialBankStatementProcessor.isProvincialBankStatement(file)) {
                return new ProvincialBankStatementProcessor();
            }
            // Auto-detect Banesco PDF
            if (BanescoBankStatementProcessor.isBanescoBankStatement(file)) {
                return new BanescoBankStatementProcessor();
            }
            return new PdfParser();
        } else if (name.endsWith(".txt")) {
            return new TxtParser();
        }
        throw new IllegalArgumentException("Formato de archivo no soportado: " + name);
    }
}
