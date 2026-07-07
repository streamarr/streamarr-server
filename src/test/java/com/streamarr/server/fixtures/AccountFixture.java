package com.streamarr.server.fixtures;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.UserAccount;
import java.util.UUID;

public final class AccountFixture {

  private AccountFixture() {}

  public static UserAccount.UserAccountBuilder<?, ?> defaultAccountBuilder() {
    return UserAccount.builder()
        .email("user-" + UUID.randomUUID() + "@example.com")
        .displayName("Test User")
        .passwordHash("{noop}not-a-real-hash")
        .accountRole(AccountRole.USER)
        .enabled(true);
  }
}
