package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.DeviceBoundSessionException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The ceremony's device gate: a TV never steps up, before any session or password work. */
@Tag("UnitTest")
@DisplayName("Reauthentication Service Tests")
class ReauthenticationServiceTest {

  private final ReauthenticationService service =
      new ReauthenticationService(
          new FakeUserAccountRepository(), new FakeAuthSessionRepository(), null);

  @Test
  @DisplayName("Should reject a device-bound session before any password work")
  void shouldRejectDeviceBoundSessionBeforeAnyPasswordWork() {
    var device =
        AuthenticatedIdentityFixture.accountScopedBuilder()
            .registrationId(UUID.randomUUID())
            .build();

    // The null verifier proves the gate fires first: reaching verification would NPE instead.
    assertThatThrownBy(() -> service.reauthenticate(device, "correct horse battery staple"))
        .isInstanceOf(DeviceBoundSessionException.class);
  }
}
