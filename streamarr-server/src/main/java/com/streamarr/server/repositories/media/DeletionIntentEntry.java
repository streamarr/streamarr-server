package com.streamarr.server.repositories.media;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DeletionIntentEntry(UUID id, OffsetDateTime requestedAt) {}
