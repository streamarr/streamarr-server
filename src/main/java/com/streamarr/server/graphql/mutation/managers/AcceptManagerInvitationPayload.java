package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.dto.ManagerInvitationView;
import java.util.List;

public record AcceptManagerInvitationPayload(
    ManagerInvitationView invitation, List<AcceptManagerInvitationError> userErrors) {}
