package com.streamarr.server.domain;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/** A file that a scan found in its library's folder and skipped, and why it skipped the file. */
@Builder
public record SkippedFile(
    @NonNull UUID libraryId,
    @NonNull UUID libraryOperationId,
    @NonNull String filepathUri,
    @NonNull SkippedFileReason reason) {

  /**
   * The id of the file within its library. It derives from the library and the filepath, so a
   * rescan that skips the same file again keeps the file's place in a paged list.
   */
  public UUID id() {
    return UUID.nameUUIDFromBytes(
        (libraryId + "\n" + filepathUri).getBytes(StandardCharsets.UTF_8));
  }
}
