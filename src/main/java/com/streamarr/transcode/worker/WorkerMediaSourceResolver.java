package com.streamarr.transcode.worker;

import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.Uuid;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

final class WorkerMediaSourceResolver {

  private final Map<UUID, Path> sourceNamespaces;

  WorkerMediaSourceResolver(Map<UUID, Path> sourceNamespaces) {
    this.sourceNamespaces = sourceNamespaces;
  }

  Path resolve(MediaSourceRef source) {
    validateSegments(source.getRelativeKey());
    var root = sourceNamespaces.get(uuid(source.getSourceNamespaceId()));
    if (root == null) {
      throw new WorkerJobException("Unknown media source namespace");
    }

    try {
      var realRoot = root.toRealPath();
      var mediaFile = realRoot.resolve(source.getRelativeKey()).toRealPath();
      if (!mediaFile.startsWith(realRoot)
          || !Files.isRegularFile(mediaFile)
          || !Files.isReadable(mediaFile)) {
        throw new WorkerJobException("Media source is not a readable file in its namespace");
      }
      return mediaFile;
    } catch (IOException e) {
      throw new WorkerJobException("Media source is unavailable", e);
    }
  }

  private void validateSegments(String relativeKey) {
    if (relativeKey.indexOf('\0') >= 0) {
      throw new WorkerJobException("Media source key must not contain NUL");
    }
    if (relativeKey.indexOf('\\') >= 0) {
      throw new WorkerJobException("Media source key must use '/' separators");
    }
    if (relativeKey.length() >= 2
        && Character.isLetter(relativeKey.charAt(0))
        && relativeKey.charAt(1) == ':') {
      throw new WorkerJobException("Media source key must not contain a drive prefix");
    }
    for (var segment : relativeKey.split("/", -1)) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
        throw new WorkerJobException("Media source key contains an unsafe path segment");
      }
    }
  }

  private static UUID uuid(Uuid value) {
    return new UUID(value.getMostSignificantBits(), value.getLeastSignificantBits());
  }
}
