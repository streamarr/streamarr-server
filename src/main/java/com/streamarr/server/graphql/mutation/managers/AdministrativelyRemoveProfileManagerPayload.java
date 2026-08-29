package com.streamarr.server.graphql.mutation.managers;

import java.util.List;
import java.util.Optional;

public record AdministrativelyRemoveProfileManagerPayload(
    Optional<String> profileId, List<AdministrativelyRemoveProfileManagerError> userErrors) {}
