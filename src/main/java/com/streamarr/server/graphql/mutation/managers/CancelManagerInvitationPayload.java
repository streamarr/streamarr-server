package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.dto.ManagerInvitationView;
import java.util.List;
import java.util.Optional;

public record CancelManagerInvitationPayload(
    Optional<ManagerInvitationView> invitation, List<CancelManagerInvitationError> userErrors) {}
