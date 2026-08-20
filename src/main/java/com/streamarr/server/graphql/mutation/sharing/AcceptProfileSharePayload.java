package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.dto.ProfileShareView;
import java.util.List;

public record AcceptProfileSharePayload(
    ProfileShareView share, List<AcceptProfileShareError> userErrors) {}
