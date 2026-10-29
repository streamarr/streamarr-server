package com.streamarr.server.fixtures;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;

import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.streaming.PlaybackSessionRequest;
import com.streamarr.server.services.streaming.PlaybackSessionService;
import com.streamarr.server.services.streaming.PlaybackSessionSnapshot;
import com.streamarr.server.services.streaming.StartPlaybackSessionCommand;
import com.streamarr.server.services.streaming.StartPlaybackSessionRejection;
import com.streamarr.server.services.streaming.StreamingService;
import java.time.Duration;
import java.util.UUID;
import lombok.experimental.UtilityClass;

@UtilityClass
public class PlaybackSessionFixture {
  public static PlaybackSessionService.PlaybackSessionServiceBuilder playbackServiceBuilder(
      StreamingService streams) {
    return PlaybackSessionService.builder()
        .streamingService(streams)
        .authorityGate(new FakePlaybackAuthorityGate())
        .executor(Runnable::run)
        .maximumFormatAttempts(2)
        .sessionRetention(Duration.ofHours(24));
  }

  public static StartPlaybackSessionCommand.StartPlaybackSessionCommandBuilder
      startPlaybackBuilder() {
    return StartPlaybackSessionCommand.builder()
        .mediaFileId(UUID.randomUUID())
        .identity(identityFor(defaultPlaybackAuthorityBuilder().build()))
        .options(StreamingOptions.builder().quality(VideoQuality.AUTO).build())
        .capabilityDeclaration(
            ClientCapabilityDeclarationFixture.defaultDeclarationBuilder().build());
  }

  public static PlaybackSessionRequest playbackRequest(
      PlaybackSessionSnapshot snapshot, StartPlaybackSessionCommand command) {
    return PlaybackSessionRequest.builder()
        .playbackSessionId(snapshot.playbackSessionId())
        .identity(command.identity())
        .build();
  }

  public static PlaybackSessionSnapshot acceptedPlayback(
      Outcome<PlaybackSessionSnapshot, StartPlaybackSessionRejection> outcome) {
    return outcome.fold(
        snapshot -> snapshot,
        rejection -> {
          throw new AssertionError(rejection);
        });
  }
}
