package com.bankreconciliation.parser;

import com.bankreconciliation.model.Transaction;

import java.io.File;
import java.util.List;

/**
 * Interface for all file format parsers.
 */
public interface FileParser {

    /**
     * Parse the file and extract transactions.
     *
     * @param file   the file to parse
     * @param source BOOK or BANK
     * @return list of extracted transactions
     * @throws Exception if parsing fails
     */
    List<Transaction> parse(File file, Transaction.Source source) throws Exception;

    /**
     * Attempt to extract "Saldo Inicial" from the file header (bank statements
     * only).
     *
     * @param file the file to scan
     * @return the initial balance, or 0.0 if not found
     */
    double extractSaldoInicial(File file);
}
