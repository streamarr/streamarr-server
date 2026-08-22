package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.dto.ManagerInvitationView;
import java.util.List;
import java.util.Optional;

public record DeclineManagerInvitationPayload(
    Optional<ManagerInvitationView> invitation, List<DeclineManagerInvitationError> userErrors) {}
