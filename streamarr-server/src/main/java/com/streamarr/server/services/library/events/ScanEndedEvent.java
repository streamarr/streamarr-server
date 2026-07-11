package com.streamarr.server.services.library.events;

import java.util.UUID;

public record ScanEndedEvent(UUID libraryId) {}
