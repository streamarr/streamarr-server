package com.streamarr.server.graphql.mutation.profile;

import java.util.List;
import java.util.UUID;

public record DeleteProfilePayload(UUID deletedProfileId, List<DeleteProfileError> userErrors) {}
