package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;

@Builder
public record RenamePortableProfileCommand(UUID actingAccountId, UUID profileId, String name) {}
