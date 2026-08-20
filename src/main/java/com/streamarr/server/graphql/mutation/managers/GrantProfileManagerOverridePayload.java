package com.streamarr.server.graphql.mutation.managers;

import java.util.List;

public record GrantProfileManagerOverridePayload(
    String profileId, List<GrantProfileManagerOverrideError> userErrors) {}
