package com.streamarr.server.services.metadata.tmdb;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("TMDB Deserialization Tests")
class TmdbDeserializationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("Should deserialize movie when credits id is null")
  void shouldDeserializeMovieWhenCreditsIdIsNull() {
    var json =
        """
        {
          "id": 27205,
          "title": "Inception",
          "adult": false,
          "video": false,
          "credits": {
            "id": null,
            "cast": [],
            "crew": []
          }
        }
        """;

    var movie = objectMapper.readValue(json, TmdbMovie.class);

    assertThat(movie.getCredits().getId()).isNull();
  }
}
