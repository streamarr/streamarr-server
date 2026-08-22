package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.dto.IssuedManagerInvitation;
import java.util.List;
import java.util.Optional;

public record InviteProfileManagerPayload(
    Optional<IssuedManagerInvitation> issued, List<InviteProfileManagerError> userErrors) {}
