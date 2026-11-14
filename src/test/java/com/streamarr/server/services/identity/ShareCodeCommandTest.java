package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Share Code Command Tests")
class ShareCodeCommandTest {

  private static final String CODE = "BCDF-GHJK";
  private static final String IP_ADDRESS = "192.0.2.40";

  @Test
  @DisplayName("Should leave the typed code out when a presented code is rendered")
  void shouldLeaveTypedCodeOutWhenPresentedCodeIsRendered() {
    var command = ShareCodeCommand.builder().code(CODE).ipAddress(IP_ADDRESS).build();

    assertThat(command.toString()).contains("code=REDACTED").doesNotContain(CODE);
  }

  @Test
  @DisplayName("Should leave the typed code out when a presented code's builder is rendered")
  void shouldLeaveTypedCodeOutWhenPresentedCodesBuilderIsRendered() {
    var builder = ShareCodeCommand.builder().code(CODE).ipAddress(IP_ADDRESS);

    assertThat(builder.toString()).contains("REDACTED").doesNotContain(CODE);
  }
}
