package com.streamarr.server.domain.streaming;

import java.time.Duration;
import lombok.NonNull;

/**
 * Where one source stream ends, on the zero-based timeline of the probe's keyframe positions (ADR
 * 0037, early ends). A completed job attempt reports it for a chosen stream whose last sample it
 * read, and a keyframe packet scan for each stream whose packets it read. The worker reads it from
 * the source's packets, not from the container header.
 */
public record SourceStreamEnd(int streamIndex, @NonNull Duration endTime) {}
