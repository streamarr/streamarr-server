package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.nio.file.Files;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DirectPlaySourceService {

  private final StreamingService streamingService;

  /**
   * Opens the source file of a direct-play stream session for one player request. The request
   * passes the live playback-authority check and updates the session's last access, as each HLS
   * request does (ADR 0018). Empty when the session is missing or delivers HLS, when the authority
   * no longer allows playback, or when the file is no longer readable.
   */
  public Optional<DirectPlaySource> open(
      @NonNull UUID streamSessionId, @NonNull AuthenticatedIdentity identity) {
    var request =
        PlaybackRequest.builder()
            .streamSessionId(streamSessionId)
            .identity(identity)
            .delivery(StreamDelivery.DIRECT_PLAY)
            .build();

    return streamingService.accessSession(request).flatMap(DirectPlaySourceService::readableSource);
  }

  private static Optional<DirectPlaySource> readableSource(StreamSession session) {
    var path = session.getSourcePath();
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      return Optional.empty();
    }

    return session.getSourceContainer().map(container -> new DirectPlaySource(path, container));
  }
}
