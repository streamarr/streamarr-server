package com.streamarr.server.domain.media;

public enum MediaFileStatus {
    UNMATCHED,
    FILENAME_PARSING_FAILED,
    MEDIA_SEARCH_FAILED,
    FAILED_METADATA_ENRICHMENT,
    MATCHED
}
