package com.streamarr.server.graphql.mutation.credentials;

import com.streamarr.server.graphql.dto.AccountInvitationView;
import java.util.List;

public record CancelAccountInvitationPayload(
    AccountInvitationView invitation, List<CancelAccountInvitationError> userErrors) {}
