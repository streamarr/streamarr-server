package com.streamarr.server.services.events.library;

import java.util.UUID;
import lombok.NonNull;

/** A recount replaced a library's authoritative totals; consumers observe it after commit. */
public record LibraryCountsChangedEvent(@NonNull UUID libraryId) {}
