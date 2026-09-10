package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.services.events.library.MediaFileProbeRequested;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.probe.PersistedProbeReader;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlaybackProbeService {

  private final PersistedProbeReader reader;
  private final ApplicationEventPublisher eventPublisher;

  public Outcome<MediaProbe, CreateStreamSessionRejection> read(@NonNull UUID mediaFileId) {
    var stored = reader.find(mediaFileId);
    if (stored.isEmpty()) {
      eventPublisher.publishEvent(new MediaFileProbeRequested(mediaFileId));
      return Outcome.rejected(new CreateStreamSessionRejection.ProbeNotReady());
    }

    return switch (stored.get().outcome()) {
      case ProbeOutcome.Success success -> Outcome.accepted(success.mediaProbe());
      case ProbeOutcome.Failure(var reason) ->
          Outcome.rejected(new CreateStreamSessionRejection.ProbeFailed(reason));
    };
  }
}
