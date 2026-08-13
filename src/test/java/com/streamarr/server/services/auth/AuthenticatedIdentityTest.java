package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.AccountRole;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Authenticated Identity Tests")
class AuthenticatedIdentityTest {

  @Test
  @DisplayName("Should construct profile identity without household claims")
  void shouldConstructProfileIdentityWithoutHouseholdClaims() {
    var profileId = UUID.randomUUID();

    var identity =
        AuthenticatedIdentity.builder()
            .accountId(UUID.randomUUID())
            .role(AccountRole.USER)
            .authSessionId(UUID.randomUUID())
            .scope(TokenScope.PROFILE)
            .profileId(profileId)
            .build();

    assertThat(identity.profileId()).isEqualTo(profileId);
  }
}
