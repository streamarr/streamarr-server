package com.streamarr.server.fixtures;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.UserAccount;
import java.util.UUID;

public final class AccountFixture {

  private AccountFixture() {}

  /** A HouseholdAdmin member of a fresh Household with a fresh Personal Profile id. */
  public static UserAccount.UserAccountBuilder<?, ?> defaultAccountBuilder() {
    return UserAccount.builder()
        .email("user-" + UUID.randomUUID() + "@example.com")
        .displayName("Test User")
        .passwordHash("{noop}not-a-real-hash")
        .householdId(UUID.randomUUID())
        .householdRole(HouseholdRole.ADMIN)
        .personalProfileId(UUID.randomUUID())
        .serverAdmin(false);
  }
}
