package com.streamarr.server.domain.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Company Entity Tests")
class CompanyTest {

  @Test
  @DisplayName("Should be equal when both have the same sourceId")
  void shouldBeEqualWhenBothHaveSameSourceId() {
    var a = Company.builder().sourceId("174").name("Warner Bros.").build();
    var b = Company.builder().sourceId("174").name("Warner Bros. Pictures").build();

    assertThat(a).isEqualTo(b);
  }

  @Test
  @DisplayName("Should not be equal when sourceIds differ")
  void shouldNotBeEqualWhenSourceIdsDiffer() {
    var a = Company.builder().sourceId("174").name("Warner Bros.").build();
    var b = Company.builder().sourceId("923").name("Legendary Pictures").build();

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  @DisplayName("Should not be equal when sourceId is null on either side")
  void shouldNotBeEqualWhenSourceIdIsNull() {
    var a = Company.builder().name("Unknown").build();
    var b = Company.builder().name("Unknown").build();

    assertThat(a).isNotEqualTo(b);
  }
}
