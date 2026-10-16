package com.streamarr.server.domain.streaming;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.NonNull;

/** Matroska's per-stream statistics tags, NUMBER_OF_FRAMES and DURATION. */
public record MatroskaStatistics(
    @NonNull OptionalLong numberOfFrames, @NonNull Optional<Duration> duration) {}
