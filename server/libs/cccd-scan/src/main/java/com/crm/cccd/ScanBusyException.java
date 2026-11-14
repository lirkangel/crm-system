package com.crm.cccd;

/** Too many QR decodes are running at once; the caller should answer 503 and ask the client to retry. */
public class ScanBusyException extends RuntimeException {

    public ScanBusyException() {
        super("QR decoder busy");
    }
}
