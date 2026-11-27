package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.dto.ManagerInvitationDetails;
import java.util.List;
import java.util.Optional;

public record LookUpManagerInvitationCodePayload(
    Optional<ManagerInvitationDetails> invitation,
    List<LookUpManagerInvitationCodeError> userErrors) {}
