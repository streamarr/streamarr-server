package com.streamarr.server.services.parsers.video;

import lombok.Builder;

// TODO: Rename
public record VideoFileMetadata(String title, String year) {
    @Builder
    public VideoFileMetadata {
    }
}
