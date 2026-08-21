package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.dto.ProfileShareView;
import java.util.List;
import java.util.Optional;

public record AcceptProfileSharePayload(
    Optional<ProfileShareView> share, List<AcceptProfileShareError> userErrors) {}
