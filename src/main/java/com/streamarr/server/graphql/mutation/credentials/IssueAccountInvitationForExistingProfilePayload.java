package com.streamarr.server.graphql.mutation.credentials;

import com.streamarr.server.graphql.dto.IssuedAccountInvitation;
import java.util.List;
import java.util.Optional;

public record IssueAccountInvitationForExistingProfilePayload(
    Optional<IssuedAccountInvitation> issued, List<IssueAccountInvitationError> userErrors) {}
