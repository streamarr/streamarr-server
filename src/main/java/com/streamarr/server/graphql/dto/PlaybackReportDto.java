package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.services.playbackreport.PlaybackReport;
import com.streamarr.server.services.playbackreport.PlayerFigures;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

/** {@code readAt}, {@code mediaFileId} and {@code note} are null when absent. */
@Builder
public record PlaybackReportDto(
    String id,
    String sharedAt,
    String readAt,
    String profileId,
    String profileName,
    String playbackSessionId,
    String streamSessionId,
    String mediaFileId,
    String note,
    String requestedChoice,
    String playbackChoice,
    List<PlaybackDecisionDto> decisions,
    PlayerFigures player,
    ClientCapabilityDeclaration capabilityDeclaration) {

  public static PlaybackReportDto from(PlaybackReport report) {
    return PlaybackReportDto.builder()
        .id(report.id().toString())
        .sharedAt(report.sharedAt().toString())
        .readAt(report.readAt().map(Instant::toString).orElse(null))
        .profileId(report.profileId().toString())
        .profileName(report.profileName())
        .playbackSessionId(report.playbackSessionId().toString())
        .streamSessionId(report.streamSessionId().toString())
        .mediaFileId(report.mediaFileId().map(UUID::toString).orElse(null))
        .note(report.note().orElse(null))
        .requestedChoice(report.requestedChoice().name())
        .playbackChoice(report.playbackChoice().name())
        .decisions(report.decisions().stream().map(PlaybackDecisionDto::from).toList())
        .player(report.player())
        .capabilityDeclaration(report.capabilityDeclaration())
        .build();
  }
}
