package com.streamarr.server.graphql.mutation.managers;

import java.util.List;
import java.util.Optional;

public record RemoveProfileManagerOverridePayload(
    Optional<String> profileId, List<RemoveProfileManagerOverrideError> userErrors) {}
