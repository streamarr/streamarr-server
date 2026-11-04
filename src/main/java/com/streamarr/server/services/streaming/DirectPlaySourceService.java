package com.streamarr.server.services.streaming;

import com.streamarr.server.config.RemoteTranscodeProperties;
import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DirectPlaySourceService {

  private final StreamingService streamingService;
  private final RemoteTranscodeProperties remoteSource;

  /**
   * Opens the source file of a direct-play stream session for one player request. The request
   * passes the live playback-authority check and updates the session's last access, as each HLS
   * request does (ADR 0018). The answer names the file's real path, which must lie inside the
   * configured media root, so a link never leads outside it. Empty when the session is missing or
   * delivers HLS, when the authority no longer allows playback, or when the file is no longer a
   * readable file inside the media root.
   */
  public Optional<DirectPlaySource> open(
      @NonNull UUID streamSessionId, @NonNull AuthenticatedIdentity identity) {
    var request =
        PlaybackRequest.builder()
            .streamSessionId(streamSessionId)
            .identity(identity)
            .delivery(StreamDelivery.DIRECT_PLAY)
            .build();

    return streamingService.accessSession(request).flatMap(this::readableSource);
  }

  private Optional<DirectPlaySource> readableSource(StreamSession session) {
    var container = session.getSourceContainer();
    if (container.isEmpty()) {
      return Optional.empty();
    }

    return realPathInsideMediaRoot(session.getSourcePath())
        .filter(Files::isReadable)
        .map(path -> new DirectPlaySource(path, container.get()));
  }

  private Optional<Path> realPathInsideMediaRoot(Path sourcePath) {
    try {
      var mediaRoot = Path.of(remoteSource.sourceRoot()).toRealPath();
      var realPath = sourcePath.toRealPath();
      if (!realPath.startsWith(mediaRoot) || !Files.isRegularFile(realPath)) {
        return Optional.empty();
      }

      return Optional.of(realPath);
    } catch (IOException _) {
      return Optional.empty();
    }
  }
}
