package com.streamarr.server.services.library;

import java.util.UUID;
import lombok.NonNull;

/** A ServerAdmin's request that the running operation of one kind stops (ADR 0027). */
public record LibraryOperationStop(@NonNull UUID libraryId, @NonNull LibraryOperationKind kind) {}
