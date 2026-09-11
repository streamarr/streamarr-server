package com.streamarr.server.domain.task;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * The inputs of one probe task instance: which file, and the snapshot and version it was seen at.
 */
@Builder(toBuilder = true)
public record ProbeRequest(
    @NonNull UUID mediaFileId,
    @NonNull UUID libraryId,
    @NonNull String filepathUri,
    @NonNull SourceFileSnapshot snapshot,
    int probeVersion) {}
