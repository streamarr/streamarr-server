package com.streamarr.server.graphql.mutation.lifecycle;

import java.util.List;
import java.util.Optional;

public record DeleteMyAccountPayload(
    Optional<String> accountId, List<DeleteMyAccountError> userErrors) {}
