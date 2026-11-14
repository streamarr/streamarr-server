package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.services.serversettings.LifetimeSetting;
import java.util.List;
import java.util.Optional;

public record ChangeInvitationLifetimePayload(
    Optional<LifetimeSetting> invitationLifetimeSetting,
    List<ChangeInvitationLifetimeError> userErrors) {}
