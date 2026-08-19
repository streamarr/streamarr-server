package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;

import com.streamarr.server.domain.auth.AccountAuthorityFacts;
import com.streamarr.server.jooq.generated.enums.AccountRole;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;

@RequiredArgsConstructor
public class UserAccountRepositoryCustomImpl implements UserAccountRepositoryCustom {

  private final DSLContext dsl;

  @Override
  public Optional<AccountAuthorityFacts> findAuthorityFacts(UUID accountId) {
    return dsl.select(USER_ACCOUNT.ENABLED, USER_ACCOUNT.ACCOUNT_ROLE)
        .from(USER_ACCOUNT)
        .where(USER_ACCOUNT.ID.eq(accountId))
        .fetchOptional(
            row -> new AccountAuthorityFacts(row.value1(), row.value2() == AccountRole.ADMIN));
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
