package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.dto.ManagerInvitationView;
import java.util.List;
import java.util.Optional;

public record AcceptManagerInvitationPayload(
    Optional<ManagerInvitationView> invitation, List<AcceptManagerInvitationError> userErrors) {}
