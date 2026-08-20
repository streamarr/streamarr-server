package com.streamarr.server.graphql.mutation.profile;

import com.streamarr.server.graphql.dto.ProfileAdministration;
import java.util.List;

public record ClearProfileContentCeilingPayload(
    ProfileAdministration profile, List<ClearProfileContentCeilingError> userErrors) {}
