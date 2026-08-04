package com.streamarr.server.services.events.library;

import java.util.UUID;

public record ItemProcessedEvent(UUID libraryId) {}
