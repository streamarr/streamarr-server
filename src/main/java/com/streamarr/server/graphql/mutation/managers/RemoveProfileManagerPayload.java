package com.streamarr.server.graphql.mutation.managers;

import java.util.List;
import java.util.Optional;

public record RemoveProfileManagerPayload(
    Optional<String> profileId, List<RemoveProfileManagerError> userErrors) {}
