package com.bankreconciliation;

import com.bankreconciliation.model.Transaction;
import com.bankreconciliation.model.Transaction.Status;

import java.util.*;

/**
 * Motor de Conciliación Automática.
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
     * Ejecuta SOLO la conciliación exacta (Pass 1 y 2).
     */
    public static Result performExactMatch(List<Transaction> bookTransactions,
            List<Transaction> bankTransactions) {
        int matchedByRef = 0;
        int matchedByAmount = 0;

        // Pass 1: Match by Ref + Amount
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

        // Pass 2: Match by Amount
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

        return new Result(matchedByRef, matchedByAmount, 0, 0, 0, 0, 0);
    }

    /**
     * Asigna estados a las transacciones que quedaron PENDING.
     */
    public static Result assignUnmatchedStatuses(List<Transaction> bookTransactions,
            List<Transaction> bankTransactions) {
        int dna = 0, rne = 0, anr = 0, cnr = 0;

        for (Transaction bookTx : bookTransactions) {
            if (bookTx.isPending()) {
                if (bookTx.getDeposit() > 0) {
                    bookTx.setStatus(Status.DNA);
                    dna++;
                } else if (bookTx.getWithdrawal() > 0) {
                    bookTx.setStatus(Status.RNE);
                    rne++;
                }
            }
        }

        for (Transaction bankTx : bankTransactions) {
            if (bankTx.isPending()) {
                if (bankTx.getDeposit() > 0) {
                    bankTx.setStatus(Status.ANR);
                    anr++;
                } else if (bankTx.getWithdrawal() > 0) {
                    bankTx.setStatus(Status.CNR);
                    cnr++;
                }
            }
        }

        int unmatched = dna + rne + anr + cnr;
        return new Result(0, 0, unmatched, dna, rne, anr, cnr);
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

    private static String normalizeRef(String ref) {
        if (ref == null)
            return "";
        // Remove leading zeros and trim
        String normalized = ref.trim().replaceAll("^0+", "");
        return normalized.isEmpty() ? ref.trim() : normalized;
    }

    // Removes "-1", "-2" suffixes to get the base reference
    private static String getBaseRef(String ref) {
        String norm = normalizeRef(ref);
        // Remove "-X" suffix if present
        return norm.replaceAll("-\\d+$", "");
    }

    private static boolean amountsMatch(Transaction a, Transaction b) {
        return Math.abs(a.getAbsAmount() - b.getAbsAmount()) < 0.01;
    }

    private static long amountKey(Transaction t) {
        return Math.round(t.getAbsAmount() * 100);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Near-Match Selection (1:1 and Many:1)
    // ═══════════════════════════════════════════════════════════════════════════

    public static final double NEAR_MATCH_TOLERANCE = 1.00;

    /**
     * Un "NearMatch" puede involucrar múltiples transacciones de un lado
     * contra una (o varias) del otro. (Ej: 3 cobros vs 1 depósito bancario)
     */
    public record NearMatch(List<Transaction> books, List<Transaction> banks, double difference) {
        public String differenceFormatted() {
            return String.format("%.2f", Math.abs(difference));
        }

        // Convenience constructors for 1:1 match
        public NearMatch(Transaction book, Transaction bank, double diff) {
            this(List.of(book), List.of(bank), diff);
        }
    }

    public static List<NearMatch> findNearMatches(List<Transaction> bookTransactions,
            List<Transaction> bankTransactions) {
        List<NearMatch> nearMatches = new ArrayList<>();
        Set<Integer> usedBookIds = new HashSet<>();
        Set<Integer> usedBankIds = new HashSet<>();

        // Group bank txs by Normalized Reference for quick lookup
        Map<String, List<Transaction>> bankByRef = new HashMap<>();
        for (Transaction bt : bankTransactions) {
            if (bt.isPending()) {
                String ref = normalizeRef(bt.getReference());
                if (!ref.isEmpty()) {
                    bankByRef.computeIfAbsent(ref, k -> new ArrayList<>()).add(bt);
                }
            }
        }

        // -------------------------------------------------------------------
        // STRATEGY 1: Many-to-One by Base Reference (e.g. 17013, 17013-1 -> 17013)
        // -------------------------------------------------------------------
        // Group pending book txs by their base ref
        Map<String, List<Transaction>> bookGroups = new HashMap<>();
        for (Transaction bookTx : bookTransactions) {
            if (!bookTx.isPending())
                continue;
            String baseRef = getBaseRef(bookTx.getReference());
            if (!baseRef.isEmpty()) {
                bookGroups.computeIfAbsent(baseRef, k -> new ArrayList<>()).add(bookTx);
            }
        }

        for (Map.Entry<String, List<Transaction>> entry : bookGroups.entrySet()) {
            String baseRef = entry.getKey();
            List<Transaction> group = entry.getValue();

            // Only consider if we have a matching bank transaction for this base ref
            List<Transaction> bankCandidates = bankByRef.get(baseRef);
            if (bankCandidates != null) {
                // Determine group total amount
                double groupTotal = group.stream().mapToDouble(Transaction::getAbsAmount).sum();

                // Try to find a single bank transaction that matches this total
                for (Transaction bankTx : bankCandidates) {
                    if (usedBankIds.contains(bankTx.getId()))
                        continue;

                    double diff = Math.abs(groupTotal - bankTx.getAbsAmount());

                    // Match found? (Exact or Near)
                    if (diff <= NEAR_MATCH_TOLERANCE) {
                        double signedDiff = groupTotal - bankTx.getAbsAmount();

                        // Check if any in group is already used (shouldn't be, validation step)
                        boolean anyUsed = group.stream().anyMatch(t -> usedBookIds.contains(t.getId()));
                        if (!anyUsed) {
                            nearMatches.add(new NearMatch(new ArrayList<>(group), List.of(bankTx), signedDiff));
                            group.forEach(t -> usedBookIds.add(t.getId()));
                            usedBankIds.add(bankTx.getId());
                            break; // Match found for this group
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------------
        // STRATEGY 2: 1-to-1 Match by Reference (remaining)
        // -------------------------------------------------------------------
        // (Copied primarily from previous logic but checking used lists)
        for (Transaction bookTx : bookTransactions) {
            if (!bookTx.isPending())
                continue;
            if (usedBookIds.contains(bookTx.getId()))
                continue;

            String ref = normalizeRef(bookTx.getReference());
            if (ref.isEmpty())
                continue;

            List<Transaction> candidates = bankByRef.get(ref);
            if (candidates != null) {
                Transaction bestMatch = null;
                double bestDiff = Double.MAX_VALUE;

                for (Transaction bankTx : candidates) {
                    if (usedBankIds.contains(bankTx.getId()))
                        continue;

                    double diff = Math.abs(bookTx.getAbsAmount() - bankTx.getAbsAmount());

                    // Must be within tolerance and better than current best
                    if (diff <= NEAR_MATCH_TOLERANCE && diff < bestDiff) {
                        // Avoid 0 diff if it was somehow missed by exact match?
                        // Actually exact match (0.01) should have been caught.
                        // But if it wasn't caught (e.g. pass 1 skipped it for some reason), catch it
                        // here.
                        if (diff > 0.00) {
                            bestMatch = bankTx;
                            bestDiff = diff;
                        }
                    }
                }

                if (bestMatch != null) {
                    double signedDiff = bookTx.getAbsAmount() - bestMatch.getAbsAmount();
                    nearMatches.add(new NearMatch(bookTx, bestMatch, signedDiff));
                    usedBookIds.add(bookTx.getId());
                    usedBankIds.add(bestMatch.getId());
                }
            }
        }

        // -------------------------------------------------------------------
        // STRATEGY 3: 1-to-1 Match by Amount (remaining)
        // -------------------------------------------------------------------
        for (Transaction bookTx : bookTransactions) {
            if (!bookTx.isPending())
                continue;
            if (usedBookIds.contains(bookTx.getId()))
                continue;

            Transaction bestMatch = null;
            double bestDiff = NEAR_MATCH_TOLERANCE;

            for (Transaction bankTx : bankTransactions) {
                if (!bankTx.isPending())
                    continue;
                if (usedBankIds.contains(bankTx.getId()))
                    continue;

                double diff = Math.abs(bookTx.getAbsAmount() - bankTx.getAbsAmount());

                if (diff > 0.00 && diff <= NEAR_MATCH_TOLERANCE && diff < bestDiff) {
                    bestMatch = bankTx;
                    bestDiff = diff;
                }
            }

            if (bestMatch != null) {
                double signedDiff = bookTx.getAbsAmount() - bestMatch.getAbsAmount();
                nearMatches.add(new NearMatch(bookTx, bestMatch, signedDiff));
                usedBookIds.add(bookTx.getId());
                usedBankIds.add(bestMatch.getId());
            }
        }

        return nearMatches;
    }

    public static List<NearMatch> findLargeDifferences(List<Transaction> bookTransactions,
            List<Transaction> bankTransactions) {
        List<NearMatch> largeDiffs = new ArrayList<>();

        // Map bank transactions by normalized reference
        Map<String, List<Transaction>> bankByRef = new HashMap<>();
        for (Transaction bt : bankTransactions) {
            String ref = normalizeRef(bt.getReference());
            if (!ref.isEmpty()) {
                bankByRef.computeIfAbsent(ref, k -> new ArrayList<>()).add(bt);
            }
        }

        for (Transaction bookTx : bookTransactions) {
            String ref = normalizeRef(bookTx.getReference());
            if (ref.isEmpty())
                continue;

            List<Transaction> candidates = bankByRef.get(ref);
            if (candidates != null) {
                for (Transaction bankTx : candidates) {
                    double diff = Math.abs(bookTx.getAbsAmount() - bankTx.getAbsAmount());

                    // We are looking for differences > 1.00
                    // And we only care about UNMATCHED transactions (Pending status)
                    // Or maybe even matched ones? User said "monitor", likely pending ones.
                    // "no se den para aprobar... me va a servir para chequear".
                    // Let's filter for PENDING only to avoid noise from already reconciled items.
                    if (bookTx.isPending() && bankTx.isPending()) {
                        if (diff > NEAR_MATCH_TOLERANCE) { // diff > 1.00
                            double signedDiff = bookTx.getAbsAmount() - bankTx.getAbsAmount();
                            largeDiffs.add(new NearMatch(bookTx, bankTx, signedDiff));
                        }
                    }
                }
            }
        }

        return largeDiffs;
    }
}
