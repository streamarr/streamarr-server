package com.streamarr.server.graphql.mutation.lifecycle;

import com.streamarr.server.graphql.dto.ProfileAdministration;
import java.util.List;

public record TransferProfilePayload(
    ProfileAdministration profile, List<TransferProfileError> userErrors) {}
