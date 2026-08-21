package com.streamarr.server.graphql.mutation.lifecycle;

import java.util.List;
import java.util.Optional;

public record ForceDeleteProfilePayload(
    Optional<String> profileId, List<ForceDeleteProfileError> userErrors) {}
