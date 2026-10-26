package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;

import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.filepath.OnDiskSpelling;
import com.streamarr.transcode.v1.MediaSourceRef;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

final class RemoteMediaSourceMapper {

  private final UUID sourceNamespaceId;
  private final Path configuredRoot;
  private volatile Path spelledRoot;

  RemoteMediaSourceMapper(UUID sourceNamespaceId, Path sourceRoot) {
    this.sourceNamespaceId = sourceNamespaceId;
    this.configuredRoot = sourceRoot.toAbsolutePath().normalize();
  }

  MediaSourceRef map(Path sourcePath) {
    var root = sourceRoot();
    var normalized = sourcePath.toAbsolutePath().normalize();
    if (!normalized.startsWith(root) || normalized.equals(root)) {
      throw new TranscodeException("Media source is outside the configured source namespace");
    }

    return MediaSourceRef.newBuilder()
        .setSourceNamespaceId(toProto(sourceNamespaceId))
        .setRelativeKey(relativeKey(root, normalized))
        .build();
  }

  // Libraries store their paths as the file system spells them, so the root is spelled the same
  // way. A root that is not mounted yet, or cannot be listed, holds no library. It keeps its
  // configured spelling until its stored spelling can be read.
  private Path sourceRoot() {
    var root = spelledRoot;
    if (root != null) {
      return root;
    }

    try {
      root = OnDiskSpelling.of(configuredRoot);
    } catch (IOException _) {
      return configuredRoot;
    }

    spelledRoot = root;
    return root;
  }

  private static String relativeKey(Path root, Path normalized) {
    try {
      return FilepathCodec.relativePathOf(root, normalized);
    } catch (IllegalArgumentException exception) {
      throw new TranscodeException("Media source name is not valid UTF-8", exception);
    }
  }
}
