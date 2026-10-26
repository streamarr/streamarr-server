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
  private final Path sourceRoot;

  RemoteMediaSourceMapper(UUID sourceNamespaceId, Path sourceRoot) {
    this.sourceNamespaceId = sourceNamespaceId;
    this.sourceRoot = onDiskSpellingOf(sourceRoot.toAbsolutePath().normalize());
  }

  // Libraries store their paths as the file system spells them, so the root is spelled the same
  // way. A root that does not exist or cannot be listed holds no library, so it stays as
  // configured.
  private static Path onDiskSpellingOf(Path root) {
    try {
      return OnDiskSpelling.of(root);
    } catch (IOException _) {
      return root;
    }
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
