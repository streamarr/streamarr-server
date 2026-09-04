package com.streamarr.server.graphql.mutation.identity.lifecycle;

import java.util.List;
import java.util.Optional;

public record AdministrativelyDeleteAccountPayload(
    Optional<String> accountId, List<AdministrativelyDeleteAccountError> userErrors) {}
