package com.streamarr.server.graphql.mutation.managers;

import java.util.List;

public record RemoveProfileManagerPayload(
    String profileId, List<RemoveProfileManagerError> userErrors) {}
