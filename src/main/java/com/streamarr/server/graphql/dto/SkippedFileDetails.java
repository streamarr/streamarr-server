package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.SkippedFile;
import com.streamarr.server.domain.SkippedFileReason;

public record SkippedFileDetails(String filepathUri, SkippedFileReason reason) {

  public static SkippedFileDetails from(SkippedFile skippedFile) {
    return new SkippedFileDetails(skippedFile.filepathUri(), skippedFile.reason());
  }
}
