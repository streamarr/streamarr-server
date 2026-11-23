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

/**
 * A playback report that a viewer shared with the Server Admin: the server's decisions for the
 * viewer's playback session and its capability declaration, the player's figures, and the viewer's
 * note. {@code streamSessionId} names the format attempt whose figures the player captured. {@code
 * readAt} is when a ServerAdmin first marked it read. {@code mediaFileId} is empty once the media
 * file is removed.
 */
@Builder(toBuilder = true)
public record PlaybackReport(
    @NonNull UUID id,
    @NonNull Instant sharedAt,
    @NonNull Optional<Instant> readAt,
    @NonNull UUID profileId,
    @NonNull String profileName,
    @NonNull UUID playbackSessionId,
    @NonNull UUID streamSessionId,
    @NonNull Optional<UUID> mediaFileId,
    @NonNull Optional<String> note,
    @NonNull VideoQuality requestedChoice,
    @NonNull VideoQuality playbackChoice,
    @NonNull List<PlaybackDecision> decisions,
    @NonNull PlayerFigures player,
    @NonNull ClientCapabilityDeclaration capabilityDeclaration) {

  public PlaybackReport {
    decisions = List.copyOf(decisions);
  }
}
