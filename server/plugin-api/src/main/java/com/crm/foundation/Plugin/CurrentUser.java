package com.crm.foundation.Plugin;

import java.util.Optional;
import java.util.UUID;

/** The authenticated user of the current request, as seen by a plugin. */
public interface CurrentUser {

    /** The user's id, or empty when the caller is not authenticated. */
    Optional<UUID> id();
}
