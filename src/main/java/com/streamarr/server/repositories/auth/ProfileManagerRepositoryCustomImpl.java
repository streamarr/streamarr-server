package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.ProfileManager.PROFILE_MANAGER;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;

@RequiredArgsConstructor
public class ProfileManagerRepositoryCustomImpl implements ProfileManagerRepositoryCustom {

  private final DSLContext dsl;

  @Override
  public boolean tryGrantDirectManagement(UUID accountId, UUID profileId) {
    return dsl.insertInto(PROFILE_MANAGER)
            .set(PROFILE_MANAGER.ACCOUNT_ID, accountId)
            .set(PROFILE_MANAGER.PROFILE_ID, profileId)
            .onConflictDoNothing()
            .execute()
        > 0;
  }

  @Override
  public boolean tryRevokeDirectManagement(UUID accountId, UUID profileId) {
    return dsl.deleteFrom(PROFILE_MANAGER)
            .where(PROFILE_MANAGER.ACCOUNT_ID.eq(accountId))
            .and(PROFILE_MANAGER.PROFILE_ID.eq(profileId))
            .execute()
        > 0;
  }
}
