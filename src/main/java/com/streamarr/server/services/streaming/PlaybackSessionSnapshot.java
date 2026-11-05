package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.VideoQuality;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * {@code playbackChoice} is the playback choice that plays now: {@code requestedChoice}, or Auto
 * once Original reaches an encode (ADR 0052). {@code formatChange} says why the output differs from
 * the output that {@code requestedChoice} asked for. {@code decisions} holds the decision of each
 * format attempt that started, the initial format first (#751).
 */
@Builder
public record PlaybackSessionSnapshot(
    @NonNull UUID playbackSessionId,
    @NonNull UUID mediaFileId,
    int formatAttemptCount,
    int maximumFormatAttempts,
    @NonNull VideoQuality requestedChoice,
    @NonNull VideoQuality playbackChoice,
    @NonNull Optional<PlaybackFormatChange> formatChange,
    @NonNull List<VideoQuality> offeredChoices,
    @NonNull PlaybackFormatAttempt currentFormatAttempt,
    @NonNull List<PlaybackDecision> decisions) {}
