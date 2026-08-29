package com.streamarr.server.graphql.mutation.managers;

import java.util.List;
import java.util.Optional;

public record AdministrativelyGrantProfileManagerPayload(
    Optional<String> profileId, List<AdministrativelyGrantProfileManagerError> userErrors) {}
