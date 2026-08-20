package com.streamarr.server.graphql.mutation.devices;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.identity.DeviceRejections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Every service rejection maps to exactly its schema error type. */
@Tag("UnitTest")
@DisplayName("Device Errors Tests")
class DeviceErrorsTest {

  @Test
  @DisplayName("Should map every revocation and block rejection to its schema error")
  void shouldMapEveryRevocationAndBlockRejectionToItsSchemaError() {
    assertThat(DeviceErrors.toRevokeError(new DeviceRejections.RegistrationNotFound()))
        .isInstanceOf(RegistrationNotFoundError.class);
    assertThat(DeviceErrors.toRevokeError(new DeviceRejections.RegistrationNotActive()))
        .isInstanceOf(RegistrationNotActiveError.class);
    assertThat(DeviceErrors.toBlockError(new DeviceRejections.HouseholdNotFound()))
        .isInstanceOf(HouseholdNotFoundError.class);
    assertThat(DeviceErrors.toBlockError(new DeviceRejections.EsnRequired()))
        .isInstanceOf(EsnRequiredError.class);
    assertThat(DeviceErrors.toBlockError(new DeviceRejections.ReasonRequired()))
        .isInstanceOf(ReasonRequiredError.class);
    assertThat(DeviceErrors.toBlockError(new DeviceRejections.AlreadyBlocked()))
        .isInstanceOf(EsnAlreadyBlockedError.class);
    assertThat(DeviceErrors.toBlockServerWideError(new DeviceRejections.EsnRequired()))
        .isInstanceOf(EsnRequiredError.class);
    assertThat(DeviceErrors.toBlockServerWideError(new DeviceRejections.ReasonRequired()))
        .isInstanceOf(ReasonRequiredError.class);
    assertThat(DeviceErrors.toBlockServerWideError(new DeviceRejections.AlreadyBlocked()))
        .isInstanceOf(EsnAlreadyBlockedError.class);
    assertThat(DeviceErrors.toBlockServerWideError(new DeviceRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
    assertThat(DeviceErrors.toUnblockError(new DeviceRejections.HouseholdNotFound()))
        .isInstanceOf(HouseholdNotFoundError.class);
    assertThat(DeviceErrors.toUnblockError(new DeviceRejections.EsnRequired()))
        .isInstanceOf(EsnRequiredError.class);
    assertThat(DeviceErrors.toUnblockError(new DeviceRejections.BlockNotFound()))
        .isInstanceOf(EsnBlockNotFoundError.class);
    assertThat(DeviceErrors.toUnblockServerWideError(new DeviceRejections.EsnRequired()))
        .isInstanceOf(EsnRequiredError.class);
    assertThat(DeviceErrors.toUnblockServerWideError(new DeviceRejections.BlockNotFound()))
        .isInstanceOf(EsnBlockNotFoundError.class);
  }
}
