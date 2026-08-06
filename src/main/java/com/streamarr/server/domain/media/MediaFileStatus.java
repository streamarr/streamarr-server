package com.streamarr.server.domain.media;

public enum MediaFileStatus {
  UNMATCHED,
  METADATA_PARSING_FAILED,
  METADATA_NOT_FOUND,
  METADATA_UNAVAILABLE,
  ENRICHMENT_FAILED,
  MATCHED
}
