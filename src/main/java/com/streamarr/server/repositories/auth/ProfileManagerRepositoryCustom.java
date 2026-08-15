package com.streamarr.server.repositories.auth;

import java.util.UUID;

public interface ProfileManagerRepositoryCustom {

  boolean insertIfAbsent(UUID accountId, UUID profileId);
}
