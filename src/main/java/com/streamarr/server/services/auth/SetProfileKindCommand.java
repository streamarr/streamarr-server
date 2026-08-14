package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.ProfileKind;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record SetProfileKindCommand(
    @NonNull UUID actingAccountId, @NonNull UUID profileId, @NonNull ProfileKind kind) {}
