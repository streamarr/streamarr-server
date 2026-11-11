package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record LifetimeOutOfRangeError(String message, List<String> inputPath)
    implements ChangeShareCodeLifetimeError, ChangeInvitationLifetimeError, InputMutationError {}
