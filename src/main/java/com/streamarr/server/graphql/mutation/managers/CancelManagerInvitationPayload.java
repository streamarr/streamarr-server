package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.dto.ManagerInvitationDetails;
import java.util.List;
import java.util.Optional;

public record CancelManagerInvitationPayload(
    Optional<ManagerInvitationDetails> invitation, List<CancelManagerInvitationError> userErrors) {}
