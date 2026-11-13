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
 * zero-based timeline of the probe's keyframe positions, as a probed end time does. {@code
 * fromPacketScan} says that a keyframe packet scan read it. A scan fails when it cannot read the
 * source to its end, while a job attempt's FFmpeg can stop at a read error and still complete. So
 * only a scanned end time replaces an end time that the container header states, and only a scanned
 * end time moves a stream session's end.
 */
@Builder(toBuilder = true)
public record LearnedStreamEnd(
    @NonNull UUID mediaFileId,
    @NonNull SourceFileSnapshot snapshot,
    int streamIndex,
    @NonNull Duration endTime,
    boolean fromPacketScan) {}
