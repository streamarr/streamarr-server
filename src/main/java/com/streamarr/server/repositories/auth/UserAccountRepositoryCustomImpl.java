package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;
import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;

import com.streamarr.server.domain.auth.AccountAuthorityFacts;
import com.streamarr.server.jooq.generated.enums.ProfileShareStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

@RequiredArgsConstructor
public class UserAccountRepositoryCustomImpl implements UserAccountRepositoryCustom {

  private final DSLContext dsl;

  @Override
  public Optional<AccountAuthorityFacts> findAuthorityFacts(UUID accountId) {
    return dsl.select(USER_ACCOUNT.ENABLED, USER_ACCOUNT.SERVER_ADMIN)
        .from(USER_ACCOUNT)
        .where(USER_ACCOUNT.ID.eq(accountId))
        .forShare()
        .fetchOptional(record -> new AccountAuthorityFacts(record.value1(), record.value2()));
  }

  @Override
  public boolean mayUseHousehold(UUID accountId, UUID householdId) {
    var personalProfileShared =
        DSL.exists(
            DSL.selectOne()
                .from(PROFILE_HOUSEHOLD_SHARE)
                .where(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(USER_ACCOUNT.PERSONAL_PROFILE_ID))
                .and(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(householdId))
                .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.ACTIVE)));
    return dsl.fetchExists(
        dsl.selectOne()
            .from(USER_ACCOUNT)
            .where(USER_ACCOUNT.ID.eq(accountId))
            .and(USER_ACCOUNT.HOUSEHOLD_ID.eq(householdId).or(personalProfileShared)));
  }

  @Override
  public List<UUID> findUsableHouseholdIds(UUID accountId) {
    var isMembership = PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(USER_ACCOUNT.HOUSEHOLD_ID);
    return dsl.selectDistinct(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID, isMembership)
        .from(USER_ACCOUNT)
        .join(PROFILE_HOUSEHOLD_SHARE)
        .on(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(USER_ACCOUNT.PERSONAL_PROFILE_ID))
        .where(USER_ACCOUNT.ID.eq(accountId))
        .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.ACTIVE))
        .orderBy(isMembership.desc(), PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.asc())
        .fetch(record -> record.value1());
  }

  @Override
  public boolean lockIfCredentialsUnchanged(UUID accountId, String expectedPasswordHash) {
    return dsl.select(USER_ACCOUNT.ID)
        .from(USER_ACCOUNT)
        .where(USER_ACCOUNT.ID.eq(accountId))
        .and(USER_ACCOUNT.PASSWORD_HASH.eq(expectedPasswordHash))
        .and(USER_ACCOUNT.ENABLED.isTrue())
        .forUpdate()
        .fetchOptional()
        .isPresent();
  }
}
