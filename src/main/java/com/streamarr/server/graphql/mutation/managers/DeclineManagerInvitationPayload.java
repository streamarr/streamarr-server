package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.dto.ManagerInvitationDetails;
import java.util.List;
import java.util.Optional;

public record DeclineManagerInvitationPayload(
    Optional<ManagerInvitationDetails> invitation,
    List<DeclineManagerInvitationError> userErrors) {}
