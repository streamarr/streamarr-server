package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;

import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.filepath.FilepathNameNormalizer;
import com.streamarr.transcode.v1.MediaSourceRef;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

final class RemoteMediaSourceMapper {

  private final UUID sourceNamespaceId;
  private final Path configuredRoot;
  private final AtomicReference<Path> rootWithFileSystemNames = new AtomicReference<>();

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

  // Libraries store their paths with the names that the file system stores, so the root uses
  // those names too. A root that is not mounted yet, or cannot be listed, holds no library. It
  // keeps its configured names until the stored names can be read.
  private Path sourceRoot() {
    var root = rootWithFileSystemNames.get();
    if (root != null) {
      return root;
    }

    try {
      root = FilepathNameNormalizer.normalizeToFileSystemNames(configuredRoot);
    } catch (IOException _) {
      return configuredRoot;
    }

    rootWithFileSystemNames.set(root);
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
