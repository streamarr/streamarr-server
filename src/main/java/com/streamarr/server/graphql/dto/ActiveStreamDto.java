package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.streaming.PlaybackSnapshot;
import com.streamarr.server.services.streaming.ActiveStream;
import java.util.List;
import lombok.Builder;

/** Every nullable field is null when the active stream has no such value. */
@Builder
public record ActiveStreamDto(
    String playbackSessionId,
    ProfileDto profile,
    String deviceName,
    String mediaFileId,
    BaseCollectable<?> media,
    Integer positionSeconds,
    String state,
    PlaybackDecisionDto decision,
    List<String> workers) {

  public record ProfileDto(String id, String name) {}

  /** {@code activeStream} is null once the stream ended or failed. */
  public record ChangeDto(String playbackSessionId, ActiveStreamDto activeStream) {}

  public static ActiveStreamDto from(ActiveStream stream) {
    var timeline = stream.timeline();
    return ActiveStreamDto.builder()
        .playbackSessionId(stream.playbackSessionId().toString())
        .profile(new ProfileDto(stream.profileId().toString(), stream.profileName().orElse(null)))
        .deviceName(stream.deviceName().orElse(null))
        .mediaFileId(stream.mediaFileId().toString())
        .media(stream.media().orElse(null))
        .positionSeconds(timeline.map(PlaybackSnapshot::positionSeconds).orElse(null))
        .state(timeline.map(snapshot -> snapshot.state().name()).orElse(null))
        .decision(stream.decision().map(PlaybackDecisionDto::from).orElse(null))
        .workers(stream.workers())
        .build();
  }
}
