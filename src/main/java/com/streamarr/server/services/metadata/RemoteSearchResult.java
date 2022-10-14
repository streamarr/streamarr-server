package com.streamarr.server.services.metadata;

import com.streamarr.server.domain.ExternalSourceType;
import lombok.Builder;

public record RemoteSearchResult(String title,
                                 String externalId,
                                 ExternalSourceType externalSourceType) {
    @Builder
    public RemoteSearchResult {
    }
}
