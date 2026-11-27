package com.streamarr.server.graphql.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Issued Share Code Details Tests")
class IssuedShareCodeDetailsTest {

  @Test
  @DisplayName("Should leave the code out when an issued code's answer is rendered")
  void shouldLeaveCodeOutWhenIssuedCodesAnswerIsRendered() {
    var id = UUID.randomUUID();
    var issued =
        new IssuedShareCodeDetails(
            ShareCodeDetails.builder().id(id).issuedByDisplayName("Issuer").build(), "BCDF-GHJK");

    assertThat(issued.toString())
        .contains("code=REDACTED", id.toString())
        .doesNotContain("BCDF-GHJK");
  }
}
