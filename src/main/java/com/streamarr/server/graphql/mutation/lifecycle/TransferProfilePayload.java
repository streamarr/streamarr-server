package com.streamarr.server.graphql.mutation.lifecycle;

import com.streamarr.server.graphql.dto.ProfileAdministration;
import java.util.List;
import java.util.Optional;

public record TransferProfilePayload(
    Optional<ProfileAdministration> profile, List<TransferProfileError> userErrors) {}
