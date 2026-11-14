package com.streamarr.server.graphql.mutation.managers;

public record ManagerInvitationLimitReachedError(String message)
    implements InviteProfileManagerError {}
