package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.VideoQuality;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * {@code playbackChoice} is the playback choice that plays now: {@code requestedChoice}, or Auto
 * once Original reaches an encode (ADR 0052). {@code formatChange} says why the output differs from
 * the output that {@code requestedChoice} asked for. {@code decisions} holds the decision of each
 * format attempt that started, the initial format first (#751). {@code capabilityDeclaration} is
 * the declaration that every format attempt replays. {@code attemptPlaybackChoices} holds the
 * playback choice that each started format attempt played, by its stream session: {@code
 * playbackChoice} can change to Auto while a replacement is still pending.
 */
@Builder
public record PlaybackSessionSnapshot(
    @NonNull UUID playbackSessionId,
    @NonNull UUID mediaFileId,
    @NonNull ClientCapabilityDeclaration capabilityDeclaration,
    int formatAttemptCount,
    int maximumFormatAttempts,
    @NonNull VideoQuality requestedChoice,
    @NonNull VideoQuality playbackChoice,
    @NonNull Optional<PlaybackFormatChange> formatChange,
    @NonNull List<VideoQuality> offeredChoices,
    @NonNull PlaybackFormatAttempt currentFormatAttempt,
    @NonNull List<PlaybackDecision> decisions,
    @NonNull Map<UUID, VideoQuality> attemptPlaybackChoices) {}
