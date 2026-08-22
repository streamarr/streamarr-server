package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.dto.ProfileShareView;
import java.util.List;
import java.util.Optional;

public record ForceEndProfileSharePayload(
    Optional<ProfileShareView> share, List<ForceEndProfileShareError> userErrors) {}
