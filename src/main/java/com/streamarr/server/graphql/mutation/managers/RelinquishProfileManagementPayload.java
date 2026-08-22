package com.streamarr.server.graphql.mutation.managers;

import java.util.List;
import java.util.Optional;

public record RelinquishProfileManagementPayload(
    Optional<String> profileId, List<RelinquishProfileManagementError> userErrors) {}
