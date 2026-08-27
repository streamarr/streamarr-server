package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.dto.ProfileShareDetails;
import java.util.List;
import java.util.Optional;

public record CancelProfileSharePayload(
    Optional<ProfileShareDetails> share, List<CancelProfileShareError> userErrors) {}
