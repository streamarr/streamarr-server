package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.Sequences.HOUSEHOLD_MEMBERSHIP_VERSION_SEQ;
import static com.streamarr.server.jooq.generated.tables.AccountProfile.ACCOUNT_PROFILE;
import static com.streamarr.server.jooq.generated.tables.HouseholdMembership.HOUSEHOLD_MEMBERSHIP;

import com.streamarr.server.domain.auth.AccountProfile;
import com.streamarr.server.domain.auth.MembershipVersionChange;
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
  private final CounterChangePublisher counterChangePublisher;

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

    var versionChange = bumpMembershipVersion(link, auditUser);
    counterChangePublisher.publishMembership(versionChange);
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

    var versionChange = bumpMembershipVersion(link, auditUser);
    counterChangePublisher.publishMembership(versionChange);
    return true;
  }

  /**
   * The globally allocated bump and its cache-refresh event live here so no future profile-link
   * path can forget either half of the invariant (ADR 0015 counter propagation).
   */
  private MembershipVersionChange bumpMembershipVersion(AccountProfile link, UUID auditUser) {
    // The membership row is FK-guaranteed: every account_profile row references it, so the
    // link being granted or revoked proves it exists in this transaction.
    var version =
        dsl.update(HOUSEHOLD_MEMBERSHIP)
            .set(
                HOUSEHOLD_MEMBERSHIP.MEMBERSHIP_VERSION, HOUSEHOLD_MEMBERSHIP_VERSION_SEQ.nextval())
            .set(HOUSEHOLD_MEMBERSHIP.LAST_MODIFIED_ON, OffsetDateTime.now(ZoneOffset.UTC))
            .set(HOUSEHOLD_MEMBERSHIP.LAST_MODIFIED_BY, auditUser)
            .where(HOUSEHOLD_MEMBERSHIP.ACCOUNT_ID.eq(link.getAccountId()))
            .and(HOUSEHOLD_MEMBERSHIP.HOUSEHOLD_ID.eq(link.getHouseholdId()))
            .returning(HOUSEHOLD_MEMBERSHIP.MEMBERSHIP_VERSION)
            .fetchSingle(HOUSEHOLD_MEMBERSHIP.MEMBERSHIP_VERSION);
    return new MembershipVersionChange(link.getAccountId(), link.getHouseholdId(), version);
  }
}
