package com.streamarr.server.services.events.library;

import java.util.UUID;
import lombok.NonNull;

/** A library's committed totals changed since the last push to subscribers. */
public record LibraryCountsChangedEvent(@NonNull UUID libraryId) {}
