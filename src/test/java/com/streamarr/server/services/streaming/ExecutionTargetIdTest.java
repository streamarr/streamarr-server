package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Execution Target Id Tests")
class ExecutionTargetIdTest {

  @Test
  @DisplayName("Should carry its value when the value names a target")
  void shouldCarryItsValueWhenTheValueNamesATarget() {
    assertThat(new ExecutionTargetId("worker-a").value()).isEqualTo("worker-a");
    assertThat(ExecutionTargetId.LOCAL.value()).isEqualTo("local");
  }

  @Test
  @DisplayName("Should reject construction when the value is null")
  void shouldRejectConstructionWhenTheValueIsNull() {
    assertThatThrownBy(() -> new ExecutionTargetId(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("value");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t"})
  @DisplayName("Should reject construction when the value is blank")
  void shouldRejectConstructionWhenTheValueIsBlank(String value) {
    assertThatThrownBy(() -> new ExecutionTargetId(value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }
}
