package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountRole;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Authenticated Identity Tests")
class AuthenticatedIdentityTest {

  @Test
  @DisplayName("Should reject profile identity without household context")
  void shouldRejectProfileIdentityWithoutHouseholdContext() {
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(UUID.randomUUID())
            .role(AccountRole.USER)
            .sessionId(UUID.randomUUID())
            .scope(TokenScope.PROFILE)
            .profileId(UUID.randomUUID());

    assertThatThrownBy(identity::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject profile identity without household role")
  void shouldRejectProfileIdentityWithoutHouseholdRole() {
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(UUID.randomUUID())
            .role(AccountRole.USER)
            .sessionId(UUID.randomUUID())
            .scope(TokenScope.PROFILE)
            .householdId(UUID.randomUUID())
            .profileId(UUID.randomUUID());

    assertThatThrownBy(identity::build).isInstanceOf(IllegalArgumentException.class);
  }
}
