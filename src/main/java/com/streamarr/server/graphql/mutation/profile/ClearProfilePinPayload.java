package com.streamarr.server.graphql.mutation.profile;

import com.streamarr.server.graphql.dto.ProfileAdministration;
import java.util.List;

public record ClearProfilePinPayload(
    ProfileAdministration profile, List<ClearProfilePinError> userErrors) {}
