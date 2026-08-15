package com.streamarr.server.services.auth;

import java.util.UUID;

record PreparedProfileDeletion(UUID actingAccountId, UUID profileId) {}
