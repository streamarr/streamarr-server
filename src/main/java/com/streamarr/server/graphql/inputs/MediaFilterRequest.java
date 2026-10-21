package com.streamarr.server.graphql.inputs;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record MediaFilterRequest(
    @NonNull UUID libraryId,
    @NonNull UUID profileId,
    MediaSortInput sort,
    MediaFilterInput filter) {}
