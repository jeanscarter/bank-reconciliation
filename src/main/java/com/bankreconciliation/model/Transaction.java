package com.bankreconciliation.model;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

public class Transaction {

    public enum Status {
        PENDING("Pendiente", new java.awt.Color(158, 158, 158)),
        OPC("OPC", new java.awt.Color(76, 175, 80)),
        DNA("DNA", new java.awt.Color(255, 152, 0)),
        RNE("RNE", new java.awt.Color(244, 67, 54)),
        ANR("ANR", new java.awt.Color(156, 39, 176)),
        CNR("CNR", new java.awt.Color(33, 150, 243));

        private final String label;
        private final java.awt.Color color;

        Status(String label, java.awt.Color color) {
            this.label = label;
            this.color = color;
        }

        public String getLabel() { return label; }
        public java.awt.Color getColor() { return color; }
    }

    public enum Source { BOOK, BANK }

    private static final AtomicInteger ID_GEN = new AtomicInteger(1);

    private final int id;
    private final LocalDate date;
    private final String reference;
    private final String description;
    private final double deposit;
    private final double withdrawal;
    private final Source source;
    private Status status;
    private int matchedId;

    public Transaction(LocalDate date, String reference, String description,
                       double deposit, double withdrawal, Source source) {
        this.id = ID_GEN.getAndIncrement();
        this.date = date;
        this.reference = reference;
        this.description = description;
        this.deposit = deposit;
        this.withdrawal = withdrawal;
        this.source = source;
        this.status = Status.PENDING;
        this.matchedId = -1;
    }

    public int getId() { return id; }
    public LocalDate getDate() { return date; }
    public String getReference() { return reference; }
    public String getDescription() { return description; }
    public double getDeposit() { return deposit; }
    public double getWithdrawal() { return withdrawal; }
    public Source getSource() { return source; }
    public Status getStatus() { return status; }
    public int getMatchedId() { return matchedId; }

    public void setStatus(Status status) { this.status = status; }
    public void setMatchedId(int matchedId) { this.matchedId = matchedId; }

    public double getNetAmount() {
        return deposit - withdrawal;
    }

    public double getAbsAmount() {
        return deposit > 0 ? deposit : withdrawal;
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }
}
