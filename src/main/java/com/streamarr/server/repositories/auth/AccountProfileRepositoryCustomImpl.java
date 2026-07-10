package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.Sequences.HOUSEHOLD_MEMBERSHIP_VERSION_SEQ;
import static com.streamarr.server.jooq.generated.tables.AccountProfile.ACCOUNT_PROFILE;
import static com.streamarr.server.jooq.generated.tables.HouseholdMembership.HOUSEHOLD_MEMBERSHIP;

import com.streamarr.server.domain.auth.AccountProfile;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    bumpMembershipVersion(link, auditUser);
  }

  @Override
  @Transactional
  public boolean revokeProfileLink(AccountProfile link) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);

    var rowsAffected =
        dsl.deleteFrom(ACCOUNT_PROFILE)
            .where(ACCOUNT_PROFILE.ACCOUNT_ID.eq(link.getAccountId()))
            .and(ACCOUNT_PROFILE.HOUSEHOLD_ID.eq(link.getHouseholdId()))
            .and(ACCOUNT_PROFILE.PROFILE_ID.eq(link.getProfileId()))
            .execute();

    if (rowsAffected == 0) {
      return false;
    }

    bumpMembershipVersion(link, auditUser);
    return true;
  }

  private void bumpMembershipVersion(AccountProfile link, UUID auditUser) {
    dsl.update(HOUSEHOLD_MEMBERSHIP)
        .set(HOUSEHOLD_MEMBERSHIP.MEMBERSHIP_VERSION, HOUSEHOLD_MEMBERSHIP_VERSION_SEQ.nextval())
        .set(HOUSEHOLD_MEMBERSHIP.LAST_MODIFIED_ON, OffsetDateTime.now(ZoneOffset.UTC))
        .set(HOUSEHOLD_MEMBERSHIP.LAST_MODIFIED_BY, auditUser)
        .where(HOUSEHOLD_MEMBERSHIP.ACCOUNT_ID.eq(link.getAccountId()))
        .and(HOUSEHOLD_MEMBERSHIP.HOUSEHOLD_ID.eq(link.getHouseholdId()))
        .execute();
  }
}
