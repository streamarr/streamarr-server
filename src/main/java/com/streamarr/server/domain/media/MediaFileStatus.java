package com.streamarr.server.domain.media;

public enum MediaFileStatus {
    UNMATCHED,
    FILENAME_PARSING_FAILED,
    SEARCH_FAILED,
    METADATA_ENRICHMENT_FAILED,
    MATCHED
}
