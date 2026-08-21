package com.streamarr.server.graphql.mutation.profile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record DeleteProfilePayload(
    Optional<UUID> deletedProfileId, List<DeleteProfileError> userErrors) {}
