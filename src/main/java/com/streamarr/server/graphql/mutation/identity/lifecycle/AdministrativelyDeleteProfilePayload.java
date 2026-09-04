package com.streamarr.server.graphql.mutation.identity.lifecycle;

import java.util.List;
import java.util.Optional;

public record AdministrativelyDeleteProfilePayload(
    Optional<String> profileId, List<AdministrativelyDeleteProfileError> userErrors) {}
