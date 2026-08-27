package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Email Address Validator Tests")
class EmailAddressValidatorTest {

  @ParameterizedTest(name = "Should report blank for \"{0}\"")
  @NullSource
  @ValueSource(strings = {"", " ", "\t\n"})
  @DisplayName("Should report blank when the candidate has no visible characters")
  void shouldReportBlankWhenCandidateHasNoVisibleCharacters(String candidate) {
    assertThat(EmailAddressValidator.validate(candidate))
        .isEqualTo(new EmailAddressValidator.Blank());
  }

  @ParameterizedTest(name = "Should report malformed for \"{0}\"")
  @ValueSource(
      strings = {
        "kai",
        "kai@",
        "@example.com",
        "kai example.com",
        "kai@example",
        "kai@@example.com",
        "kai@example..com",
        "kai@.example.com",
        "kai@example.com.",
        "kai@exam ple.com"
      })
  @DisplayName("Should report malformed when the candidate is not shaped like an address")
  void shouldReportMalformedWhenCandidateIsNotShapedLikeAnAddress(String candidate) {
    assertThat(EmailAddressValidator.validate(candidate))
        .isEqualTo(new EmailAddressValidator.Malformed());
  }

  @Test
  @DisplayName("Should report malformed without recursing when a many-label domain ends with a dot")
  void shouldReportMalformedWithoutRecursingWhenManyLabelDomainEndsWithDot() {
    // Deciding the shape must not recurse per label: this input once overflowed the stack.
    var candidate = "kai@" + "label.".repeat(100_000);

    assertThat(EmailAddressValidator.validate(candidate))
        .isEqualTo(new EmailAddressValidator.Malformed());
  }

  @Test
  @DisplayName("Should strip surrounding whitespace and keep case when the candidate is valid")
  void shouldStripSurroundingWhitespaceAndKeepCaseWhenCandidateIsValid() {
    assertThat(EmailAddressValidator.validate("  Kai+streamarr@Example.co.uk \n"))
        .isEqualTo(new EmailAddressValidator.Valid("Kai+streamarr@Example.co.uk"));
  }
}
