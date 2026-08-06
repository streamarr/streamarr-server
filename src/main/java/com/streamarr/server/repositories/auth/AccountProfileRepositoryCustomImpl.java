package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.AccountProfile.ACCOUNT_PROFILE;

import com.streamarr.server.domain.auth.AccountProfile;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class AccountProfileRepositoryCustomImpl implements AccountProfileRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
  @Transactional
  public void linkProfile(AccountProfile link) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);

    dsl.insertInto(ACCOUNT_PROFILE)
        .set(ACCOUNT_PROFILE.ACCOUNT_ID, link.getAccountId())
        .set(ACCOUNT_PROFILE.HOUSEHOLD_ID, link.getHouseholdId())
        .set(ACCOUNT_PROFILE.PROFILE_ID, link.getProfileId())
        .set(ACCOUNT_PROFILE.CREATED_BY, auditUser)
        .set(ACCOUNT_PROFILE.LAST_MODIFIED_BY, auditUser)
        .execute();
  }

  @Override
  @Transactional
  public boolean revokeProfileLink(AccountProfile link) {
    return dsl.deleteFrom(ACCOUNT_PROFILE)
            .where(ACCOUNT_PROFILE.ACCOUNT_ID.eq(link.getAccountId()))
            .and(ACCOUNT_PROFILE.HOUSEHOLD_ID.eq(link.getHouseholdId()))
            .and(ACCOUNT_PROFILE.PROFILE_ID.eq(link.getProfileId()))
            .execute()
        > 0;
  }
}
