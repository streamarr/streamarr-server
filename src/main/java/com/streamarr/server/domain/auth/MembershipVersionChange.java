package com.streamarr.server.domain.auth;

import java.util.UUID;

public record MembershipVersionChange(UUID accountId, UUID householdId, long version) {}
