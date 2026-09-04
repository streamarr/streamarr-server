package com.streamarr.server.graphql.mutation.identity.lifecycle;

import java.util.List;
import java.util.Optional;

public record DeleteMyAccountPayload(
    Optional<String> accountId, List<DeleteMyAccountError> userErrors) {}
