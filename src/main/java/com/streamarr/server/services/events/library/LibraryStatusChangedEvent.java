package com.streamarr.server.services.events.library;

import java.util.UUID;

/** A library's status or scan times were saved; listeners read the saved row by its id. */
public record LibraryStatusChangedEvent(UUID libraryId) {}
