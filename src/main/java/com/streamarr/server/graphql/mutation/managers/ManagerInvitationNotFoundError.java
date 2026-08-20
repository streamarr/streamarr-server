package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record ManagerInvitationNotFoundError(String message, List<String> inputPath)
    implements CancelManagerInvitationError,
        AcceptManagerInvitationError,
        DeclineManagerInvitationError,
        InputMutationError {}
