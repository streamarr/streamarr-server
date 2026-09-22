package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;

import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.transcode.v1.MediaSourceRef;
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

    return MediaSourceRef.newBuilder()
        .setSourceNamespaceId(toProto(sourceNamespaceId))
        .setRelativeKey(relativeKey(normalized))
        .build();
  }

  private String relativeKey(Path normalized) {
    try {
      return FilepathCodec.relativePathOf(sourceRoot, normalized);
    } catch (IllegalArgumentException exception) {
      throw new TranscodeException("Media source name is not valid UTF-8", exception);
    }
  }
}
