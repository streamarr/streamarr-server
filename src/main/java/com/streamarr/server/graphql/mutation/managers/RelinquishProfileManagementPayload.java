package com.streamarr.server.graphql.mutation.managers;

import java.util.List;

public record RelinquishProfileManagementPayload(
    String profileId, List<RelinquishProfileManagementError> userErrors) {}
