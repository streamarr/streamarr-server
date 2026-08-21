package com.streamarr.server.graphql.mutation.credentials;

import com.streamarr.server.graphql.dto.IssuedPasswordReset;
import java.util.List;
import java.util.Optional;

public record IssuePasswordResetPayload(
    Optional<IssuedPasswordReset> issued, List<IssuePasswordResetError> userErrors) {}
