package com.streamarr.server.graphql.mutation.credentials;

import com.streamarr.server.graphql.dto.AccountInvitationView;
import java.util.List;
import java.util.Optional;

public record CancelAccountInvitationPayload(
    Optional<AccountInvitationView> invitation, List<CancelAccountInvitationError> userErrors) {}
