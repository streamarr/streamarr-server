package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Frame Rate Rule Tests")
class FrameRateRuleTest {

  private static final FrameRateRuleInputs NO_INPUTS = FrameRateRuleInputs.builder().build();

  @Test
  @DisplayName("Should refuse a refused rule when it names an output frame rate")
  void shouldRefuseARefusedRuleWhenItNamesAnOutputFrameRate() {
    var rule =
        FrameRateRule.builder()
            .outputFrameRate(Optional.of(new Fraction(25, 1)))
            .basis(OutputFrameRateBasis.REFUSED)
            .inputs(NO_INPUTS);

    assertThatThrownBy(rule::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should refuse a rule that chose a basis when it names no output frame rate")
  void shouldRefuseARuleThatChoseABasisWhenItNamesNoOutputFrameRate() {
    var rule =
        FrameRateRule.builder()
            .outputFrameRate(Optional.empty())
            .basis(OutputFrameRateBasis.PACKET_SCAN)
            .inputs(NO_INPUTS);

    assertThatThrownBy(rule::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should refuse a rule when a component is missing")
  void shouldRefuseARuleWhenAComponentIsMissing() {
    var rate = Optional.of(new Fraction(25, 1));
    var basis = OutputFrameRateBasis.DECLARED_RATES;

    assertThatThrownBy(() -> new FrameRateRule(null, basis, NO_INPUTS))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new FrameRateRule(rate, null, NO_INPUTS))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new FrameRateRule(rate, basis, null))
        .isInstanceOf(NullPointerException.class);
  }
}
