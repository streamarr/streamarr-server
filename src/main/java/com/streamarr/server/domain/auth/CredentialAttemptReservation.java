package com.streamarr.server.domain.auth;

import java.util.UUID;
import lombok.NonNull;

public record CredentialAttemptReservation(
    @NonNull UUID id, @NonNull CredentialAttemptTarget target) {}
