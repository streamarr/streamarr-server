package com.streamarr.server.repositories.media;

import java.util.UUID;
import lombok.Builder;

@Builder
public record MediaFileDeletionTarget(UUID mediaFileId, UUID libraryId, UUID mediaId) {}
