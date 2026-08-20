package com.streamarr.server.graphql.mutation.credentials;

import com.streamarr.server.graphql.dto.IssuedPasswordReset;
import java.util.List;

public record IssuePasswordResetPayload(
    IssuedPasswordReset issued, List<IssuePasswordResetError> userErrors) {}
