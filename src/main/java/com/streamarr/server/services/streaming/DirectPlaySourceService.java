package com.streamarr.server.services.streaming;

import com.streamarr.server.config.RemoteTranscodeProperties;
import com.streamarr.server.domain.streaming.SourceContainer;
import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.filepath.FilepathNameNormalizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DirectPlaySourceService {

  private final StreamingService streamingService;
  private final RemoteTranscodeProperties remoteSource;
  private final DirectPlayMetrics metrics;

  /**
   * Opens the source file of a direct-play stream session for one player request. The request
   * passes the live playback-authority check and updates the session's last access, as each HLS
   * request does (ADR 0018). The file opens below the configured media root one name at a time, and
   * no name on the way may be a soft link. Empty when the session is missing or delivers HLS, when
   * the authority no longer allows playback, or when the file is no longer a readable file below
   * the media root that no soft link leads to.
   */
  public Optional<DirectPlaySource> open(
      @NonNull UUID streamSessionId, @NonNull AuthenticatedIdentity identity) {
    var request =
        PlaybackRequest.builder()
            .streamSessionId(streamSessionId)
            .identity(identity)
            .delivery(StreamDelivery.DIRECT_PLAY)
            .build();

    return streamingService.accessSession(request).flatMap(this::openSource);
  }

  private Optional<DirectPlaySource> openSource(StreamSession session) {
    var container = session.getSourceContainer();
    if (container.isEmpty()) {
      return Optional.empty();
    }

    try {
      return openBelowMediaRoot(session.getSourcePath(), container.get());
    } catch (IOException _) {
      return Optional.empty();
    }
  }

  // The media root is configuration, so its own path may pass through links. Below it, each
  // folder opens relative to the folder before it, so no name is looked up twice and a name that
  // changes after its check cannot redirect the open.
  private Optional<DirectPlaySource> openBelowMediaRoot(Path sourcePath, SourceContainer container)
      throws IOException {
    var mediaRoot =
        FilepathNameNormalizer.normalizeToFileSystemNames(Path.of(remoteSource.sourceRoot()));
    var path = sourcePath.toAbsolutePath().normalize();
    if (!path.startsWith(mediaRoot) || path.equals(mediaRoot)) {
      return Optional.empty();
    }

    try (var root = Files.newDirectoryStream(mediaRoot)) {
      if (!(root instanceof SecureDirectoryStream<Path> secureRoot)) {
        return Optional.empty();
      }

      return openBelow(secureRoot, mediaRoot.relativize(path), container);
    }
  }

  private Optional<DirectPlaySource> openBelow(
      SecureDirectoryStream<Path> folder, Path relativePath, SourceContainer container)
      throws IOException {
    var name = relativePath.getName(0);
    if (relativePath.getNameCount() == 1) {
      return openFile(folder, name, container);
    }

    try (var next = folder.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
      return openBelow(next, relativePath.subpath(1, relativePath.getNameCount()), container);
    }
  }

  private Optional<DirectPlaySource> openFile(
      SecureDirectoryStream<Path> folder, Path name, SourceContainer container) throws IOException {
    var attributes =
        folder
            .getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
            .readAttributes();
    if (!attributes.isRegularFile()) {
      return Optional.empty();
    }

    var file =
        folder.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    try {
      return Optional.of(new DirectPlaySource(file, container, metrics));
    } catch (IOException exception) {
      file.close();
      throw exception;
    }
  }
}
