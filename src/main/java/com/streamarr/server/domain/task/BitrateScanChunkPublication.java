package com.streamarr.server.domain.task;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/** One finished bitrate scan chunk to store for the file, with every measured stream. */
@Builder
public record BitrateScanChunkPublication(
    @NonNull UUID mediaFileId,
    @NonNull BitrateScanKey key,
    @NonNull StoredBitrateScanChunk chunk) {}
