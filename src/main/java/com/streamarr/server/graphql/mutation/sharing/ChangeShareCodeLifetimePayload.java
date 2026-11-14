package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.services.serversettings.LifetimeSetting;
import java.util.List;
import java.util.Optional;

public record ChangeShareCodeLifetimePayload(
    Optional<LifetimeSetting> shareCodeLifetimeSetting,
    List<ChangeShareCodeLifetimeError> userErrors) {}
