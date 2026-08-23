package com.streamarr.server.graphql.mutation.devices;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.identity.DeviceRejections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Device Errors Tests")
class DeviceErrorsTest {

  @Test
  @DisplayName("Should explain how to reauthenticate when a server-wide block requires it")
  void shouldExplainHowToReauthenticateWhenServerWideBlockRequiresIt() {
    var error =
        DeviceErrors.toBlockServerWideError(new DeviceRejections.ReauthenticationRequired());

    assertThat(error)
        .isInstanceOfSatisfying(
            ReauthenticationRequiredError.class,
            reauthentication ->
                assertThat(reauthentication.message())
                    .isEqualTo("Confirm your password before retrying this action."));
  }
}
