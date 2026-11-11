package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.ShareCode;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("New Share Code Tests")
class NewShareCodeTest {

  @Test
  @DisplayName("Should leave the code out when the answer to issuing it is rendered")
  void shouldLeaveCodeOutWhenAnswerToIssuingItIsRendered() {
    var id = UUID.randomUUID();
    var issued =
        new NewShareCode(
            new ShareCodeWithIssuer(ShareCode.builder().id(id).build(), "Issuer"), "BCDF-GHJK");

    assertThat(issued.toString())
        .contains("code=REDACTED", id.toString())
        .doesNotContain("BCDF-GHJK");
  }
}
