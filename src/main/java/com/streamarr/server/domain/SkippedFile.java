package com.streamarr.server.domain;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/** A file that a scan found in its library's folder and skipped, and why it skipped the file. */
@Builder
public record SkippedFile(
    @NonNull UUID libraryId,
    @NonNull UUID libraryOperationId,
    @NonNull String filepathUri,
    @NonNull SkippedFileReason reason) {}
