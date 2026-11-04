package com.streamarr.server.services.library;

import com.streamarr.server.config.RemoteTranscodeProperties;
import com.streamarr.server.services.filepath.FilepathNameNormalizer;
import com.streamarr.server.services.filepath.FilepathSoftLinks;
import java.io.IOException;
import java.nio.file.FileSystem;
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
   * outside the media root is checked alone. {@code folder} carries the names that the file system
   * stores.
   */
  public boolean hasSoftLinkTo(@NonNull Path folder) {
    var mediaRoot = path();
    var first = folder;
    if (folder.startsWith(mediaRoot) && !folder.equals(mediaRoot)) {
      first = mediaRoot.resolve(mediaRoot.relativize(folder).getName(0));
    }

    return FilepathSoftLinks.hasSoftLinkFrom(first, folder);
  }

  // Library folders carry the names that the file system stores, so the media root does too. A
  // media root that cannot be listed keeps its configured names.
  private Path path() {
    var configured = fileSystem.getPath(remoteSource.sourceRoot()).toAbsolutePath().normalize();
    try {
      return FilepathNameNormalizer.normalizeToFileSystemNames(configured);
    } catch (IOException _) {
      return configured;
    }
  }
}
