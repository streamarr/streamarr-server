package com.streamarr.server.services.parsers.video;

import lombok.Builder;

public record VideoFileMetadata(String title,
                                String year,
                                String externalId,
                                ExternalVideoSourceType externalSource) {
    @Builder
    public VideoFileMetadata {
    }
}
