package com.smartgn.billing.application;

public interface TaxGateway {
    class Rejected extends IllegalStateException {
        public final String code;

        public Rejected(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    record Receipt(String status, String ticket, String description, byte[] cdr) {}

    Receipt submit(String name, byte[] zip);

    Receipt poll(String ticket);

    boolean testMode();
}
