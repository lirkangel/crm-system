package com.crm.hotel.scan;

import java.util.UUID;

/** Response of a scan upload: the stored scan and the fields to pre-fill. */
public record IdScanView(UUID scanId, ScanFields fields) {
}
