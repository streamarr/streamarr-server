package com.streamarr.server.graphql.mutation.lifecycle;

import java.util.List;
import java.util.Optional;

public record DeleteAccountPayload(
    Optional<String> accountId, List<DeleteAccountError> userErrors) {}
