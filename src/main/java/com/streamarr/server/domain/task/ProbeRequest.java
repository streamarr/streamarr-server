package com.streamarr.server.domain.task;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record ProbeRequest(
    @NonNull UUID mediaFileId,
    @NonNull UUID libraryId,
    @NonNull String filepathUri,
    @NonNull SourceFileSnapshot snapshot,
    int probeVersion) {}
