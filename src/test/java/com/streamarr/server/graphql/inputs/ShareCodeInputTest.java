package com.streamarr.server.graphql.inputs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Share Code Input Tests")
class ShareCodeInputTest {

  private static final String CODE = "bcdf ghjk";

  @Test
  @DisplayName("Should leave the typed code out when the lookup input is rendered")
  void shouldLeaveTypedCodeOutWhenLookupInputIsRendered() {
    var input = new LookUpShareCodeInput(CODE);

    assertThat(input.toString()).contains("code=REDACTED").doesNotContain(CODE);
  }

  @Test
  @DisplayName("Should leave the typed code out when the offer input is rendered")
  void shouldLeaveTypedCodeOutWhenOfferInputIsRendered() {
    var profileId = UUID.randomUUID().toString();
    var input = new OfferProfileShareInput(profileId, CODE);

    assertThat(input.toString())
        .contains("shareCode=REDACTED", "profileId=" + profileId)
        .doesNotContain(CODE);
  }
}
