package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.dto.ProfileShareView;
import java.util.List;
import java.util.Optional;

public record CancelProfileSharePayload(
    Optional<ProfileShareView> share, List<CancelProfileShareError> userErrors) {}
