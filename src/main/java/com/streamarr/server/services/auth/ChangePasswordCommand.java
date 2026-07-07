package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;

@Builder
public record ChangePasswordCommand(
    UUID accountId, UUID sessionId, String currentPassword, String newPassword) {}
