package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.InvalidUserCodeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("User Code Tests")
class UserCodeTest {

  @ParameterizedTest(name = "Should normalize \"{0}\" to the stored form")
  @ValueSource(
      strings = {"BCDFGHJK", "bcdfghjk", "BCDF-GHJK", "bcdf-ghjk", "  BCDF-GHJK  ", "BCDF GHJK"})
  @DisplayName("Should accept the code when it matches a form a person actually types")
  void shouldAcceptCodeWhenMatchingFormPersonActuallyTypes(String typed) {
    assertThat(UserCode.normalize(typed)).isEqualTo("BCDFGHJK");
  }

  @ParameterizedTest(name = "Should reject \"{0}\"")
  @NullSource
  @ValueSource(
      strings = {
        "",
        "BCDFGHJ",
        "BCDFGHJKL",
        // Vowels and lookalikes are outside the alphabet on purpose.
        "ABCDFGHJ",
        "BCDF0HJK",
        "BCDF1HJK",
        "BCDFGHJ!"
      })
  @DisplayName("Should reject the code when it violates the alphabet or length")
  void shouldRejectCodeWhenViolatingAlphabetOrLength(String typed) {
    assertThatThrownBy(() -> UserCode.normalize(typed))
        .isInstanceOf(InvalidUserCodeException.class);
  }

  @Test
  @DisplayName("Should group the stored form into fours when formatting for display")
  void shouldGroupStoredFormIntoFoursWhenFormattingForDisplay() {
    assertThat(UserCode.forDisplay("BCDFGHJK")).isEqualTo("BCDF-GHJK");
  }

  @Test
  @DisplayName("Should exclude vowels and ambiguous digits when defining the alphabet")
  void shouldExcludeVowelsAndAmbiguousDigitsWhenDefiningAlphabet() {
    assertThat(UserCode.ALPHABET)
        .doesNotContain("A", "E", "I", "O", "U", "0", "1")
        .hasSize(20)
        .isEqualTo("BCDFGHJKLMNPQRSTVWXZ");
  }
}
