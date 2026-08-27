package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.dto.ManagerInvitationDetails;
import java.util.List;
import java.util.Optional;

public record AcceptManagerInvitationPayload(
    Optional<ManagerInvitationDetails> invitation, List<AcceptManagerInvitationError> userErrors) {}
