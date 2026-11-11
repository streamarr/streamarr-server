package com.streamarr.server.domain.media;

import java.time.Duration;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * Where one stream of a media file ends, as the server learned it from the file's packets (ADR
 * 0037, early ends): from the first job attempt that read the last sample of a stream whose
 * container header states no end time, or from a keyframe packet scan, which read every packet of
 * the stream. It holds for the source file of {@code snapshot} only. {@code endTime} lies on the
 * zero-based timeline of the probe's keyframe positions, as a probed end time does.
 */
@Builder(toBuilder = true)
public record LearnedStreamEnd(
    @NonNull UUID mediaFileId,
    @NonNull SourceFileSnapshot snapshot,
    int streamIndex,
    @NonNull Duration endTime) {}
