package com.crm.dental.previsit;

import com.crm.dental.scan.ScanFields;

import java.util.UUID;

/** Response of a scan upload: the stored scan and the fields to pre-fill. */
public record ScanView(UUID scanId, ScanFields fields) {
}
