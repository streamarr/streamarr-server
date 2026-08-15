package com.streamarr.server.repositories.auth;

import java.util.UUID;

public interface ProfileManagerRepositoryCustom {

  /**
 * Inserts the profile association for the account if it does not already exist.
 *
 * @param accountId the account identifier
 * @param profileId the profile identifier
 * @return {@code true} if the association was inserted, {@code false} otherwise
 */
boolean insertIfAbsent(UUID accountId, UUID profileId);
}
