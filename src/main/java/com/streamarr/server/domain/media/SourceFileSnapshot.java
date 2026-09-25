package com.streamarr.server.domain.media;

import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import lombok.NonNull;

public record SourceFileSnapshot(long size, @NonNull Instant modifiedAt) {

  public static SourceFileSnapshot of(@NonNull BasicFileAttributes attributes) {
    return new SourceFileSnapshot(attributes.size(), attributes.lastModifiedTime().toInstant());
  }
}
