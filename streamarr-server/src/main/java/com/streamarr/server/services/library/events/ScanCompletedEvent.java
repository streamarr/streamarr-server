package com.streamarr.server.services.library.events;

import java.util.UUID;

public record ScanCompletedEvent(UUID libraryId) {}
