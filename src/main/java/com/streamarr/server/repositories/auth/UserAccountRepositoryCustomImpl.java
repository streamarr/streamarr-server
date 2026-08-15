package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;
import static org.jooq.impl.DSL.inline;

import com.streamarr.server.domain.auth.UserAccount;
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

  /**
   * Finds the owner account associated with a home household.
   *
   * @param homeHouseholdId the home household identifier
   * @return the associated owner account, if one exists
   */
  @Override
  public Optional<UserAccount> findOwnerByHomeHouseholdId(UUID homeHouseholdId) {
    var query =
        dsl.selectFrom(USER_ACCOUNT)
            .where(USER_ACCOUNT.HOME_HOUSEHOLD_ID.eq(homeHouseholdId))
            .and(USER_ACCOUNT.HOUSEHOLD_ROLE.eq(inline(HouseholdRole.OWNER)));
    return JooqQueryHelper.nativeQuery(entityManager, query, UserAccount.class).stream()
        .findFirst();
  }

  /**
   * Locks the enabled account when its password hash matches the expected value.
   *
   * @param accountId the account identifier
   * @param expectedPasswordHash the password hash expected for the account
   * @return {@code true} if a matching enabled account exists and is locked, {@code false} otherwise
   */
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
