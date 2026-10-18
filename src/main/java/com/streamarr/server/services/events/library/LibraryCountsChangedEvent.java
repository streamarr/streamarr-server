package com.streamarr.server.services.events.library;

import java.util.UUID;

/** A recount replaced a library's authoritative totals; consumers observe it after commit. */
public record LibraryCountsChangedEvent(UUID libraryId) {}
