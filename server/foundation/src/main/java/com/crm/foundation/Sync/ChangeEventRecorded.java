package com.crm.foundation.Sync;

import com.crm.foundation.Domain.ChangeEvent;

/** Published by {@code ChangeEventService} inside the recording transaction; consumed after commit. */
public record ChangeEventRecorded(ChangeEvent event) {
}
