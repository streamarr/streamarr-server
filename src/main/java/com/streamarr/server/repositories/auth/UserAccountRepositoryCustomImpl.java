package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;

@RequiredArgsConstructor
public class UserAccountRepositoryCustomImpl implements UserAccountRepositoryCustom {

  private final DSLContext dsl;

  @Override
  public Optional<AccountCredential> findCredentialById(UUID accountId) {
    return dsl.select(USER_ACCOUNT.ID, USER_ACCOUNT.PASSWORD_HASH)
        .from(USER_ACCOUNT)
        .where(USER_ACCOUNT.ID.eq(accountId))
        .fetchOptional(account -> new AccountCredential(account.value1(), account.value2()));
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
