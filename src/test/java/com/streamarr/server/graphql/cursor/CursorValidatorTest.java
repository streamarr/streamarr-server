package com.streamarr.server.graphql.cursor;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Cursor Validator Tests")
class CursorValidatorTest {

  private final CursorValidator cursorValidator = new CursorValidator();

  @Test
  @DisplayName("Should not throw when validating cursor field with both values null")
  void shouldNotThrowWhenValidatingCursorFieldWithBothValuesNull() {
    assertThatNoException()
        .isThrownBy(() -> cursorValidator.validateCursorField("field", null, null));
  }

  @Test
  @DisplayName("Should throw InvalidCursorException when prior cursor field value is null")
  void shouldThrowWhenValidatingCursorFieldWithNullPrior() {
    assertThatExceptionOfType(InvalidCursorException.class)
        .isThrownBy(() -> cursorValidator.validateCursorField("field", null, "value"));
  }

  @Test
  @DisplayName("Should throw InvalidCursorException when current cursor field value is null")
  void shouldThrowWhenValidatingCursorFieldWithNullCurrent() {
    assertThatExceptionOfType(InvalidCursorException.class)
        .isThrownBy(() -> cursorValidator.validateCursorField("field", "value", null));
  }
}
