package com.streamarr.server.services.streaming.remote;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;

import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.transcode.v1.MediaSourceRef;
import java.io.File;
import java.nio.file.Path;
import java.util.UUID;

final class RemoteMediaSourceMapper {

  private final UUID sourceNamespaceId;
  private final Path sourceRoot;

  RemoteMediaSourceMapper(UUID sourceNamespaceId, Path sourceRoot) {
    this.sourceNamespaceId = sourceNamespaceId;
    this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
  }

  MediaSourceRef map(Path sourcePath) {
    var normalized = sourcePath.toAbsolutePath().normalize();
    if (!normalized.startsWith(sourceRoot) || normalized.equals(sourceRoot)) {
      throw new TranscodeException("Media source is outside the configured source namespace");
    }

    var relativeKey = sourceRoot.relativize(normalized).toString().replace(File.separatorChar, '/');
    return MediaSourceRef.newBuilder()
        .setSourceNamespaceId(toProto(sourceNamespaceId))
        .setRelativeKey(relativeKey)
        .build();
  }
}
