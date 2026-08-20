package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.dto.ProfileShareView;
import java.util.List;

public record ForceEndProfileSharePayload(
    ProfileShareView share, List<ForceEndProfileShareError> userErrors) {}
