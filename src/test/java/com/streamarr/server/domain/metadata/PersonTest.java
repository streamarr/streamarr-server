package com.streamarr.server.domain.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Person Entity Tests")
class PersonTest {

  @Test
  @DisplayName("Should be equal when both have the same sourceId")
  void shouldBeEqualWhenBothHaveSameSourceId() {
    var a = Person.builder().sourceId("6193").name("Leonardo DiCaprio").build();
    var b = Person.builder().sourceId("6193").name("Leo DiCaprio").build();

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
  }

  @Test
  @DisplayName("Should not be equal when sourceIds differ")
  void shouldNotBeEqualWhenSourceIdsDiffer() {
    var a = Person.builder().sourceId("6193").name("Leonardo DiCaprio").build();
    var b = Person.builder().sourceId("525").name("Christopher Nolan").build();

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  @DisplayName("Should not be equal when sourceId is null on either side")
  void shouldNotBeEqualWhenSourceIdIsNull() {
    var a = Person.builder().name("Unknown").build();
    var b = Person.builder().name("Unknown").build();

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  @DisplayName("Should not be equal when compared against different type")
  void shouldNotBeEqualWhenComparedAgainstDifferentType() {
    var person = Person.builder().sourceId("6193").name("Leonardo DiCaprio").build();

    assertThat(person).isNotEqualTo(Genre.builder().sourceId("6193").name("Drama").build());
  }
}
