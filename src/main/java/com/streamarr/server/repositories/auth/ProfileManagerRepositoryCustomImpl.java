package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.ProfileManager.PROFILE_MANAGER;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class ProfileManagerRepositoryCustomImpl implements ProfileManagerRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
  public boolean insertIfAbsent(UUID accountId, UUID profileId) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);
    return dsl.insertInto(PROFILE_MANAGER)
            .set(PROFILE_MANAGER.ACCOUNT_ID, accountId)
            .set(PROFILE_MANAGER.PROFILE_ID, profileId)
            .set(PROFILE_MANAGER.CREATED_BY, auditUser)
            .set(PROFILE_MANAGER.LAST_MODIFIED_BY, auditUser)
            .onConflict(PROFILE_MANAGER.ACCOUNT_ID, PROFILE_MANAGER.PROFILE_ID)
            .doNothing()
            .execute()
        > 0;
  }
}
