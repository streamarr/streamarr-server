package com.streamarr.server.domain.media;

import java.time.Duration;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * Where one stream of a media file ends, learned from the first job attempt that read the stream's
 * last sample, for a stream whose container header states no end time (ADR 0037, early ends). It
 * holds for the source file of {@code snapshot} only. {@code endTime} lies on the zero-based
 * timeline of the probe's keyframe positions, as a probed end time does.
 */
@Builder(toBuilder = true)
public record LearnedStreamEnd(
    @NonNull UUID mediaFileId,
    @NonNull SourceFileSnapshot snapshot,
    int streamIndex,
    @NonNull Duration endTime) {}
