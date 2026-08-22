package com.streamarr.server.graphql.mutation.credentials;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record EmailAlreadyUsedError(String message, List<String> inputPath)
    implements IssueAccountInvitationError, InputMutationError {}
