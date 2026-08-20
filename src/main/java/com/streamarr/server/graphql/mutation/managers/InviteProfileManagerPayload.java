package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.dto.IssuedManagerInvitation;
import java.util.List;

public record InviteProfileManagerPayload(
    IssuedManagerInvitation issued, List<InviteProfileManagerError> userErrors) {}
