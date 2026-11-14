package com.streamarr.server.graphql.inputs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Manager Invitation Code Input Tests")
class ManagerInvitationCodeInputTest {

  private static final String CODE = "4f2c8a1e.secret-for-alex";

  @Test
  @DisplayName("Should leave the presented code out when the lookup input is rendered")
  void shouldLeaveThePresentedCodeOutWhenTheLookupInputIsRendered() {
    var input = new LookUpManagerInvitationCodeInput(CODE);

    assertThat(input.toString()).contains("code=REDACTED").doesNotContain(CODE);
  }
}
