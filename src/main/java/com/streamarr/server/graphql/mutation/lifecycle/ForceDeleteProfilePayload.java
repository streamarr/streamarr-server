package com.streamarr.server.graphql.mutation.lifecycle;

import java.util.List;

public record ForceDeleteProfilePayload(
    String profileId, List<ForceDeleteProfileError> userErrors) {}
