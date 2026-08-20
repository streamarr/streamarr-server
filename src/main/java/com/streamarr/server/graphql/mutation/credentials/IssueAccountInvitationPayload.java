package com.streamarr.server.graphql.mutation.credentials;

import com.streamarr.server.graphql.dto.IssuedAccountInvitation;
import java.util.List;

public record IssueAccountInvitationPayload(
    IssuedAccountInvitation issued, List<IssueAccountInvitationError> userErrors) {}
