package com.streamarr.server.services.mutation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Outcome Tests")
class OutcomeTest {

  @Test
  @DisplayName("Should refuse a rejection with no reasons")
  void shouldRefuseRejectionWithNoReasons() {
    assertThatThrownBy(() -> Outcome.rejected(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should copy rejection reasons so later edits cannot change the outcome")
  void shouldCopyRejectionReasonsSoLaterEditsCannotChangeOutcome() {
    var reasons = new ArrayList<>(List.of("first"));

    var outcome = Outcome.<String, String>rejected(reasons);
    reasons.add("second");

    assertThat(outcome).isEqualTo(Outcome.rejected("first"));
  }
}
