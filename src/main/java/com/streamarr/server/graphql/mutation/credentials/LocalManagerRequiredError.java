package com.streamarr.server.graphql.mutation.credentials;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record LocalManagerRequiredError(String message, List<String> inputPath)
    implements IssueAccountInvitationError, InputMutationError {}
