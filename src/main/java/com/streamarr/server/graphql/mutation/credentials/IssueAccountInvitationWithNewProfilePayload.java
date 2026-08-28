package com.streamarr.server.graphql.mutation.credentials;

import com.streamarr.server.graphql.dto.IssuedAccountInvitation;
import java.util.List;
import java.util.Optional;

public record IssueAccountInvitationWithNewProfilePayload(
    Optional<IssuedAccountInvitation> issued, List<IssueAccountInvitationError> userErrors) {}
