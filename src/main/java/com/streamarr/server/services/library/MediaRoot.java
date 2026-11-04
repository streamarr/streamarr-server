package com.streamarr.server.services.library;

import com.streamarr.server.config.RemoteTranscodeProperties;
import com.streamarr.server.services.filepath.FilepathNameNormalizer;
import com.streamarr.server.services.filepath.FilepathSoftLinks;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The configured remote source root ({@code streaming.remote.source-root}), below which scans and
 * playback never follow a soft link (ADR 0045). The way to the media root may pass soft links.
 */
@Component
@RequiredArgsConstructor
public class MediaRoot {

  private final FileSystem fileSystem;
  private final RemoteTranscodeProperties remoteSource;

  /**
   * Whether {@code folder}, or a folder between the media root and it, is a soft link. A folder
   * outside the media root is checked alone.
   *
   * @throws IOException if {@code folder} does not exist or a folder on its path cannot be listed
   */
  public boolean hasSoftLinkTo(@NonNull Path folder) throws IOException {
    // A stored library folder can keep a letter case that the file system no longer stores, and
    // the media root is compared name by name.
    var stored = FilepathNameNormalizer.normalizeToFileSystemNames(folder);
    var mediaRoot = path();
    var first = stored;
    if (stored.startsWith(mediaRoot) && !stored.equals(mediaRoot)) {
      first = mediaRoot.resolve(mediaRoot.relativize(stored).getName(0));
    }

    return FilepathSoftLinks.hasSoftLinkFrom(first, stored);
  }

  /**
   * The media root in the names that the file system stores, as library folders carry them. A media
   * root that does not exist keeps its configured names, because no folder sits below it.
   *
   * @throws IOException if a folder on the media root's path cannot be read
   */
  public Path path() throws IOException {
    var configured = fileSystem.getPath(remoteSource.sourceRoot()).toAbsolutePath().normalize();
    try {
      return FilepathNameNormalizer.normalizeToFileSystemNames(configured);
    } catch (NoSuchFileException _) {
      return configured;
    }
  }
}
