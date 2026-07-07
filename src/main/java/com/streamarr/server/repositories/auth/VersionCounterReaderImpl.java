package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION;
import static com.streamarr.server.jooq.generated.tables.HouseholdMembership.HOUSEHOLD_MEMBERSHIP;
import static com.streamarr.server.jooq.generated.tables.Profile.PROFILE;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class VersionCounterReaderImpl implements VersionCounterReader {

  private final DSLContext dsl;

  @Override
  public Optional<Long> sessionVersion(UUID sessionId) {
    return dsl.select(AUTH_SESSION.SESSION_VERSION)
        .from(AUTH_SESSION)
        .where(AUTH_SESSION.ID.eq(sessionId))
        .and(AUTH_SESSION.REVOKED_AT.isNull())
        .fetchOptional(AUTH_SESSION.SESSION_VERSION);
  }

  @Override
  public Optional<Long> membershipVersion(UUID accountId, UUID householdId) {
    return dsl.select(HOUSEHOLD_MEMBERSHIP.VERSION)
        .from(HOUSEHOLD_MEMBERSHIP)
        .where(HOUSEHOLD_MEMBERSHIP.ACCOUNT_ID.eq(accountId))
        .and(HOUSEHOLD_MEMBERSHIP.HOUSEHOLD_ID.eq(householdId))
        .fetchOptional(HOUSEHOLD_MEMBERSHIP.VERSION);
  }

  @Override
  public Optional<Long> profilePolicyVersion(UUID profileId) {
    return dsl.select(PROFILE.POLICY_VERSION)
        .from(PROFILE)
        .where(PROFILE.ID.eq(profileId))
        .fetchOptional(PROFILE.POLICY_VERSION);
  }
}
