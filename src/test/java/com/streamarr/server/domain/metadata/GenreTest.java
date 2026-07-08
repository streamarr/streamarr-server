package com.streamarr.server.domain.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Genre Entity Tests")
class GenreTest {

  @Test
  @DisplayName("Should be equal when both have the same sourceId")
  void shouldBeEqualWhenBothHaveSameSourceId() {
    var a = Genre.builder().sourceId("878").name("Science Fiction").build();
    var b = Genre.builder().sourceId("878").name("Sci-Fi").build();

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
  }

  @Test
  @DisplayName("Should not be equal when sourceIds differ")
  void shouldNotBeEqualWhenSourceIdsDiffer() {
    var a = Genre.builder().sourceId("878").name("Science Fiction").build();
    var b = Genre.builder().sourceId("18").name("Drama").build();

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  @DisplayName("Should not be equal when sourceId is null on either side")
  void shouldNotBeEqualWhenSourceIdIsNull() {
    var a = Genre.builder().name("Unknown").build();
    var b = Genre.builder().name("Unknown").build();
    var withSourceId = Genre.builder().sourceId("878").name("Science Fiction").build();

    assertThat(a).isNotEqualTo(b).isNotEqualTo(withSourceId);
    assertThat(withSourceId).isNotEqualTo(a);
  }

  @Test
  @DisplayName("Should not be equal when compared against different type")
  void shouldNotBeEqualWhenComparedAgainstDifferentType() {
    var genre = Genre.builder().sourceId("878").name("Science Fiction").build();

    assertThat(genre).isNotEqualTo(Person.builder().sourceId("878").name("Same Key").build());
  }
}
