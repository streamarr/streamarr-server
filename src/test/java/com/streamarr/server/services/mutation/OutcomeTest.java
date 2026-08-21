package com.streamarr.server.services.mutation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
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
  @DisplayName("Should refuse an accepted outcome without a result")
  void shouldRefuseAcceptedOutcomeWithoutResult() {
    assertThatNullPointerException().isThrownBy(() -> Outcome.accepted(null)).withMessage("result");
  }

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

  @Test
  @DisplayName("Should fold each outcome through its own function")
  void shouldFoldEachOutcomeThroughItsOwnFunction() {
    String accepted =
        Outcome.<String, String>accepted("ok").fold(r -> "accepted:" + r, r -> "rejected");
    String rejected = Outcome.<String, String>rejected("no").fold(r -> "accepted", List::toString);

    assertThat(accepted).isEqualTo("accepted:ok");
    assertThat(rejected).isEqualTo("[no]");
  }
}
