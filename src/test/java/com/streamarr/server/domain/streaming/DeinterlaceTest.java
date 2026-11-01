package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Deinterlace Tests")
class DeinterlaceTest {

  // Apple's HLS Authoring Specification item 1.15 recommends 60p from 30i; 25i becomes 50p.
  @ParameterizedTest(name = "{0}/{1} → {2}/{3}")
  @CsvSource({
    "30000, 1001, 60000, 1001",
    "25, 1, 50, 1",
    "30, 1, 60, 1",
    "24000, 1001, 48000, 1001"
  })
  @DisplayName(
      "Should write one frame per field at twice the stored rate when twice the rate is at most 60")
  void shouldWriteOneFramePerFieldAtTwiceTheStoredRateWhenTwiceTheRateIsAtMost60(
      int storedNumerator, int storedDenominator, int outputNumerator, int outputDenominator) {
    var stored = new Fraction(storedNumerator, storedDenominator);

    var deinterlace = Deinterlace.of(FieldOrder.TT, Optional.of(stored));

    assertThat(deinterlace.output()).isEqualTo(DeinterlaceOutput.ONE_FRAME_PER_FIELD);
    assertThat(deinterlace.outputFrameRate(stored))
        .isEqualTo(new Fraction(outputNumerator, outputDenominator));
  }

  // Apple's item 1.19 forbids frame rates above 60, so a stored rate above 30 keeps its frames.
  @ParameterizedTest(name = "{0}/{1}")
  @CsvSource({"30001, 1000", "50, 1", "60000, 1001"})
  @DisplayName("Should write one frame per source frame when twice the stored rate exceeds 60")
  void shouldWriteOneFramePerSourceFrameWhenTwiceTheStoredRateExceeds60(
      int storedNumerator, int storedDenominator) {
    var stored = new Fraction(storedNumerator, storedDenominator);

    var deinterlace = Deinterlace.of(FieldOrder.BB, Optional.of(stored));

    assertThat(deinterlace.output()).isEqualTo(DeinterlaceOutput.ONE_FRAME_PER_FRAME);
    assertThat(deinterlace.outputFrameRate(stored)).isEqualTo(stored);
  }

  @Test
  @DisplayName("Should write one frame per source frame when the probe stored no frame rate")
  void shouldWriteOneFramePerSourceFrameWhenTheProbeStoredNoFrameRate() {
    var deinterlace = Deinterlace.of(FieldOrder.TB, Optional.empty());

    assertThat(deinterlace.output()).isEqualTo(DeinterlaceOutput.ONE_FRAME_PER_FRAME);
  }

  @Test
  @DisplayName("Should refuse to deinterlace when the field order is progressive")
  void shouldRefuseToDeinterlaceWhenTheFieldOrderIsProgressive() {
    assertThatThrownBy(
            () -> new Deinterlace(FieldOrder.PROGRESSIVE, DeinterlaceOutput.ONE_FRAME_PER_FIELD))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PROGRESSIVE");
  }
}
