package com.streamarr.server.domain.streaming;

import java.time.Duration;
import lombok.NonNull;

/**
 * Where a completed job attempt read one chosen source stream's last sample, on the zero-based
 * timeline of the probe's keyframe positions (ADR 0037, early ends). The worker reads it from the
 * source, not from the probe.
 */
public record SourceStreamEnd(int streamIndex, @NonNull Duration endTime) {}
