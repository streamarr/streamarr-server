package com.streamarr.server.graphql.mutation.profile;

import com.streamarr.server.graphql.dto.ProfileAdministration;
import java.util.List;
import java.util.Optional;

public record SetProfileContentCeilingPayload(
    Optional<ProfileAdministration> profile, List<SetProfileContentCeilingError> userErrors) {}
