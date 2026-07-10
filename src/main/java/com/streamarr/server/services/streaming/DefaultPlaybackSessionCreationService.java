package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.auth.PlaybackTokenIssuer;
import java.time.Duration;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DefaultPlaybackSessionCreationService implements PlaybackSessionCreationService {

  private final StreamingService streamingService;
  private final PlaybackTokenIssuer playbackTokenIssuer;
  private final StreamingProperties streamingProperties;

  @Override
  public CreatedPlaybackSession create(CreatePlaybackSessionCommand command) {
    var session =
        streamingService.createSession(
            command.mediaFileId(), command.sourceIdentity().profileId(), command.options());

    try {
      return CreatedPlaybackSession.builder()
          .sessionId(session.getSessionId())
          .transcodeMode(session.getTranscodeDecision().transcodeMode())
          .playbackToken(
              playbackTokenIssuer.issue(
                  command.sourceIdentity(), session, playbackTokenValidity(session)))
          .build();
    } catch (RuntimeException exception) {
      streamingService.destroySession(session.getSessionId());
      throw exception;
    }
  }

  private Duration playbackTokenValidity(StreamSession session) {
    return session.getMediaProbe().duration().plus(streamingProperties.sessionRetention());
  }
}
