package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;
import static org.jooq.impl.DSL.inline;

import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.jooq.generated.enums.AccountRole;
import com.streamarr.server.jooq.generated.enums.HouseholdRole;
import com.streamarr.server.repositories.JooqQueryHelper;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;

@RequiredArgsConstructor
public class UserAccountRepositoryCustomImpl implements UserAccountRepositoryCustom {

  private final DSLContext dsl;
  private final EntityManager entityManager;

  @Override
  public Optional<UserAccount> findOwnerByHomeHouseholdId(UUID homeHouseholdId) {
    var query =
        dsl.selectFrom(USER_ACCOUNT)
            .where(USER_ACCOUNT.HOME_HOUSEHOLD_ID.eq(homeHouseholdId))
            .and(USER_ACCOUNT.HOUSEHOLD_ROLE.eq(inline(HouseholdRole.OWNER)));
    return JooqQueryHelper.nativeQuery(entityManager, query, UserAccount.class).stream()
        .findFirst();
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

  @Override
  public boolean lockIfServerAdmin(UUID accountId) {
    return dsl.select(USER_ACCOUNT.ID)
        .from(USER_ACCOUNT)
        .where(USER_ACCOUNT.ID.eq(accountId))
        .and(USER_ACCOUNT.ENABLED.isTrue())
        .and(USER_ACCOUNT.ACCOUNT_ROLE.eq(AccountRole.ADMIN))
        .forUpdate()
        .fetchOptional()
        .isPresent();
  }

  @Override
  public boolean lockIfHouseholdAuthority(UUID accountId, UUID householdId) {
    var ownsHousehold =
        USER_ACCOUNT
            .HOME_HOUSEHOLD_ID
            .eq(householdId)
            .and(USER_ACCOUNT.HOUSEHOLD_ROLE.eq(HouseholdRole.OWNER));
    return dsl.select(USER_ACCOUNT.ID)
        .from(USER_ACCOUNT)
        .where(USER_ACCOUNT.ID.eq(accountId))
        .and(USER_ACCOUNT.ENABLED.isTrue())
        .and(USER_ACCOUNT.ACCOUNT_ROLE.eq(AccountRole.ADMIN).or(ownsHousehold))
        .forUpdate()
        .fetchOptional()
        .isPresent();
  }
}
