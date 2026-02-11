package com.bankreconciliation;

import com.bankreconciliation.model.Transaction;
import com.bankreconciliation.model.Transaction.Status;

import java.util.*;

/**
 * Motor de Conciliación Automática.
 * <p>
 * Empareja transacciones del Libro Contable con las del Estado de Cuenta
 * Bancario
 * usando una estrategia de 3 pasadas:
 * <ol>
 * <li><b>Pasada 1:</b> Match exacto por Referencia + Monto</li>
 * <li><b>Pasada 2:</b> Match por Monto solamente (para las restantes no
 * emparejadas)</li>
 * <li><b>Pasada 3:</b> Asignar estado a las no emparejadas
 * (DNA/RNE/ANR/CNR)</li>
 * </ol>
 * <p>
 * Reglas de estados (según la tabla de Estados de Operación):
 * <ul>
 * <li><b>OPC</b> – Operación conciliada: encontrada en ambos registros</li>
 * <li><b>DNA</b> – Depósito no abonado: Libro Bancos tiene DEBE(deposit) pero
 * no está en Extracto Bancario depósitos</li>
 * <li><b>RNE</b> – Retiro no efectuado: Libro Bancos tiene HABER(withdrawal)
 * pero no está en Extracto Bancario retiros</li>
 * <li><b>ANR</b> – Abono no registrado: Extracto Bancario tiene DEPÓSITO pero
 * no está en Libro Bancos</li>
 * <li><b>CNR</b> – Cargo no registrado: Extracto Bancario tiene RETIRO pero no
 * está en Libro Bancos</li>
 * </ul>
 */
public class ReconciliationEngine {

    /**
     * Resultado de la conciliación automática con estadísticas.
     */
    public record Result(int matchedByRef, int matchedByAmount, int unmatched,
            int dna, int rne, int anr, int cnr) {
        public int totalMatched() {
            return matchedByRef + matchedByAmount;
        }

        public String summary() {
            return String.format(
                    "Conciliación completada:\n" +
                            "  • %d emparejadas por referencia + monto\n" +
                            "  • %d emparejadas por monto\n" +
                            "  • %d sin emparejar (DNA:%d  RNE:%d  ANR:%d  CNR:%d)",
                    matchedByRef, matchedByAmount, unmatched, dna, rne, anr, cnr);
        }
    }

    /**
     * Ejecuta la conciliación automática completa.
     *
     * @param bookTransactions transacciones del Libro Contable (Source.BOOK)
     * @param bankTransactions transacciones del Estado de Cuenta Bancario
     *                         (Source.BANK)
     * @return resultado con estadísticas
     */
    public static Result autoReconcile(List<Transaction> bookTransactions,
            List<Transaction> bankTransactions) {
        int matchedByRef = 0;
        int matchedByAmount = 0;

        // ═════════════════════════════════════════════════════════════════════
        // PASADA 1: Match por Referencia + Monto
        // ═════════════════════════════════════════════════════════════════════
        // Indexar transacciones bancarias pendientes por referencia
        Map<String, List<Transaction>> bankByRef = new HashMap<>();
        for (Transaction bt : bankTransactions) {
            if (bt.isPending()) {
                String ref = normalizeRef(bt.getReference());
                bankByRef.computeIfAbsent(ref, k -> new ArrayList<>()).add(bt);
            }
        }

        for (Transaction bookTx : bookTransactions) {
            if (!bookTx.isPending())
                continue;

            String bookRef = normalizeRef(bookTx.getReference());
            List<Transaction> candidates = bankByRef.get(bookRef);
            if (candidates == null)
                continue;

            Transaction match = null;
            for (Transaction candidate : candidates) {
                if (!candidate.isPending())
                    continue;
                if (amountsMatch(bookTx, candidate)) {
                    match = candidate;
                    break;
                }
            }

            if (match != null) {
                conciliate(bookTx, match);
                matchedByRef++;
            }
        }

        // ═════════════════════════════════════════════════════════════════════
        // PASADA 2: Match por Monto (pendientes restantes)
        // ═════════════════════════════════════════════════════════════════════
        // Indexar pendientes bancarias por monto absoluto
        Map<Long, List<Transaction>> bankByAmount = new HashMap<>();
        for (Transaction bt : bankTransactions) {
            if (bt.isPending()) {
                long key = amountKey(bt);
                bankByAmount.computeIfAbsent(key, k -> new ArrayList<>()).add(bt);
            }
        }

        for (Transaction bookTx : bookTransactions) {
            if (!bookTx.isPending())
                continue;

            long key = amountKey(bookTx);
            List<Transaction> candidates = bankByAmount.get(key);
            if (candidates == null)
                continue;

            Transaction match = null;
            for (Transaction candidate : candidates) {
                if (!candidate.isPending())
                    continue;
                if (amountsMatch(bookTx, candidate)) {
                    match = candidate;
                    break;
                }
            }

            if (match != null) {
                conciliate(bookTx, match);
                matchedByAmount++;
            }
        }

        // ═════════════════════════════════════════════════════════════════════
        // PASADA 3: Asignar estados a no emparejadas
        // ═════════════════════════════════════════════════════════════════════
        int dna = 0, rne = 0, anr = 0, cnr = 0;

        for (Transaction bookTx : bookTransactions) {
            if (!bookTx.isPending())
                continue;

            if (bookTx.getDeposit() > 0) {
                // Libro tiene DEBE (deposit) → no está en CB → Depósito No Abonado
                bookTx.setStatus(Status.DNA);
                dna++;
            } else if (bookTx.getWithdrawal() > 0) {
                // Libro tiene HABER (withdrawal) → no está en CB → Retiro No Efectuado
                bookTx.setStatus(Status.RNE);
                rne++;
            }
        }

        for (Transaction bankTx : bankTransactions) {
            if (!bankTx.isPending())
                continue;

            if (bankTx.getDeposit() > 0) {
                // CB tiene DEPÓSITO → no está en Libro → Abono No Registrado
                bankTx.setStatus(Status.ANR);
                anr++;
            } else if (bankTx.getWithdrawal() > 0) {
                // CB tiene RETIRO → no está en Libro → Cargo No Registrado
                bankTx.setStatus(Status.CNR);
                cnr++;
            }
        }

        int unmatched = dna + rne + anr + cnr;
        return new Result(matchedByRef, matchedByAmount, unmatched, dna, rne, anr, cnr);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private static void conciliate(Transaction a, Transaction b) {
        a.setStatus(Status.OPC);
        a.setMatchedId(b.getId());
        b.setStatus(Status.OPC);
        b.setMatchedId(a.getId());
    }

    /**
     * Normaliza la referencia eliminando ceros a la izquierda y espacios.
     */
    private static String normalizeRef(String ref) {
        if (ref == null)
            return "";
        String normalized = ref.trim().replaceAll("^0+", "");
        return normalized.isEmpty() ? ref.trim() : normalized;
    }

    /**
     * Verifica si los montos absolutos de dos transacciones coinciden (± 0.01).
     */
    private static boolean amountsMatch(Transaction a, Transaction b) {
        return Math.abs(a.getAbsAmount() - b.getAbsAmount()) < 0.01;
    }

    /**
     * Clave de agrupación por monto: centavos redondeados para evitar
     * problemas de flotantes.
     */
    private static long amountKey(Transaction t) {
        return Math.round(t.getAbsAmount() * 100);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Near-Match Detection (diferencias de décimas/centésimas)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Tolerancia máxima para considerar un near-match (Bs.) */
    public static final double NEAR_MATCH_TOLERANCE = 1.00;

    /**
     * Par de transacciones con monto cercano pero no exacto.
     */
    public record NearMatch(Transaction book, Transaction bank, double difference) {
        public String differenceFormatted() {
            return String.format("%.2f", Math.abs(difference));
        }
    }

    /**
     * Encuentra pares de transacciones pendientes cuyos montos difieren
     * por menos de {@link #NEAR_MATCH_TOLERANCE}.
     * <p>
     * Estrategia: para cada transacción PENDING del libro, busca en las
     * bancarias PENDING aquellas con monto similar (no exacto).
     *
     * @return lista de near-matches para revisión del usuario
     */
    public static List<NearMatch> findNearMatches(List<Transaction> bookTransactions,
            List<Transaction> bankTransactions) {
        List<NearMatch> nearMatches = new ArrayList<>();
        Set<Integer> usedBankIds = new HashSet<>();

        for (Transaction bookTx : bookTransactions) {
            if (!bookTx.isPending())
                continue;

            Transaction bestMatch = null;
            double bestDiff = NEAR_MATCH_TOLERANCE;

            for (Transaction bankTx : bankTransactions) {
                if (!bankTx.isPending())
                    continue;
                if (usedBankIds.contains(bankTx.getId()))
                    continue;

                double diff = Math.abs(bookTx.getAbsAmount() - bankTx.getAbsAmount());

                // Skip exact matches (already handled by autoReconcile)
                if (diff < 0.01)
                    continue;

                // Within tolerance?
                if (diff <= NEAR_MATCH_TOLERANCE && diff < bestDiff) {
                    bestMatch = bankTx;
                    bestDiff = diff;
                }
            }

            if (bestMatch != null) {
                double signedDiff = bookTx.getAbsAmount() - bestMatch.getAbsAmount();
                nearMatches.add(new NearMatch(bookTx, bestMatch, signedDiff));
                usedBankIds.add(bestMatch.getId());
            }
        }

        return nearMatches;
    }
}
