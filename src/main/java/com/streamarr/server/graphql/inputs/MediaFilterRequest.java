package com.streamarr.server.graphql.inputs;

import java.util.UUID;
import lombok.Builder;

@Builder
public record MediaFilterRequest(
    UUID libraryId, UUID profileId, MediaSortInput sort, MediaFilterInput filter) {}
