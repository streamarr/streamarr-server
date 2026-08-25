package com.streamarr.server.graphql.mutation.profile;

import com.streamarr.server.graphql.dto.ProfileAdministration;
import java.util.List;
import java.util.Optional;

public record CreateProfilePayload(
    Optional<ProfileAdministration> profile, List<CreateProfileError> userErrors) {}
