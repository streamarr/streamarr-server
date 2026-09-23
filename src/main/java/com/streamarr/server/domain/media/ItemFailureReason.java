package com.streamarr.server.domain.media;

public enum ItemFailureReason {
  DOWNLOAD_FAILED,
  INVALID_MEDIA,
  TEMPORARY,
  MISCONFIGURED,
  SOURCE_INACCESSIBLE
}
