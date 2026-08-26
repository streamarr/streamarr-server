package com.streamarr.server.graphql.mutation.credentials;

import com.streamarr.server.graphql.dto.AccountInvitationDetails;
import java.util.List;
import java.util.Optional;

public record CancelAccountInvitationPayload(
    Optional<AccountInvitationDetails> invitation, List<CancelAccountInvitationError> userErrors) {}
