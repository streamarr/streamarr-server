package com.streamarr.server.graphql.mutation.managers;

import java.util.List;
import java.util.Optional;

public record GrantProfileManagerOverridePayload(
    Optional<String> profileId, List<GrantProfileManagerOverrideError> userErrors) {}
