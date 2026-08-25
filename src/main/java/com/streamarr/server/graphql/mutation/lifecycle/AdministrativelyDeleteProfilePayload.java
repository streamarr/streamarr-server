package com.streamarr.server.graphql.mutation.lifecycle;

import java.util.List;
import java.util.Optional;

public record AdministrativelyDeleteProfilePayload(
    Optional<String> profileId, List<AdministrativelyDeleteProfileError> userErrors) {}
