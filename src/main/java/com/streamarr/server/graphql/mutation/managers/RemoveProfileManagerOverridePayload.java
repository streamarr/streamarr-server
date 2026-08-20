package com.streamarr.server.graphql.mutation.managers;

import java.util.List;

public record RemoveProfileManagerOverridePayload(
    String profileId, List<RemoveProfileManagerOverrideError> userErrors) {}
