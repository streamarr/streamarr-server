package com.streamarr.server.services.extraction.video;

import lombok.Builder;

// TODO: Rename
public record VideoFileMetadata(String title, String year) {
    @Builder
    public VideoFileMetadata {
    }
}
