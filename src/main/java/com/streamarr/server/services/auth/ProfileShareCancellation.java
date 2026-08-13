package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileShareCancellation(UUID actingAccountId, UUID shareId) {}
