package com.streamarr.server.graphql.inputs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Profile PIN Input Tests")
class ProfilePinInputTest {

  @Test
  @DisplayName("Should redact the raw PIN when the set input is rendered")
  void shouldRedactRawPinWhenSetInputIsRendered() {
    var rawPin = "487526";
    var input = new SetProfilePinInput(UUID.randomUUID().toString(), rawPin);

    assertThat(input.toString()).contains("pin=REDACTED").doesNotContain(rawPin);
  }

  @Test
  @DisplayName("Should redact the raw PIN when the override input is rendered")
  void shouldRedactRawPinWhenOverrideInputIsRendered() {
    var rawPin = "487526";
    var input = new OverrideProfilePinInput(UUID.randomUUID().toString(), rawPin, "locked out");

    assertThat(input.toString())
        .contains("pin=REDACTED", "reason=locked out")
        .doesNotContain(rawPin);
  }
}
