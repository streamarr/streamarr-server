package com.streamarr.server.services.playbackreport;

import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.services.streaming.PlaybackDecision;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/** A report as the viewer shares it, before the server stores it. */
@Builder(toBuilder = true)
public record NewPlaybackReport(
    @NonNull Instant sharedAt,
    @NonNull UUID profileId,
    @NonNull UUID playbackSessionId,
    @NonNull UUID streamSessionId,
    @NonNull UUID submissionId,
    @NonNull UUID mediaFileId,
    @NonNull Optional<String> note,
    @NonNull VideoQuality requestedChoice,
    @NonNull VideoQuality playbackChoice,
    @NonNull List<PlaybackDecision> decisions,
    @NonNull PlayerFigures player,
    @NonNull ClientCapabilityDeclaration capabilityDeclaration) {

  public NewPlaybackReport {
    decisions = List.copyOf(decisions);
  }
}
