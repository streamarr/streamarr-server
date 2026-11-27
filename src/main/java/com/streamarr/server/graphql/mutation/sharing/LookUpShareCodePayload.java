package com.streamarr.server.graphql.mutation.sharing;

import java.util.List;
import java.util.Optional;

public record LookUpShareCodePayload(
    Optional<String> householdName, List<LookUpShareCodeError> userErrors) {}
