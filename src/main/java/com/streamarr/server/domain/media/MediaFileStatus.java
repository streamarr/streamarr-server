package com.streamarr.server.domain.media;

public enum MediaFileStatus {
  UNMATCHED,
  METADATA_PARSING_FAILED,
  METADATA_SEARCH_FAILED,
  ENRICHMENT_FAILED,
  MATCHED
}
