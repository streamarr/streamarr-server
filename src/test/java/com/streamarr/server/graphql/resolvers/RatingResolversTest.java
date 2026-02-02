package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.metadata.Rating;
import com.streamarr.server.repositories.RatingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(classes = {RatingResolvers.class})
@DisplayName("Rating Resolver Tests")
class RatingResolversTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private RatingRepository ratingRepository;

  @Test
  @DisplayName("Should return rating when valid ID provided")
  void shouldReturnRatingWhenValidIdProvided() {
    var ratingId = UUID.randomUUID();
    var rating = Rating.builder().source("IMDb").value("8.5").createdBy(UUID.randomUUID()).build();
    rating.setId(ratingId);

    when(ratingRepository.findById(ratingId)).thenReturn(Optional.of(rating));

    String source =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ rating(id: \"%s\") { source value } }", ratingId),
            "data.rating.source");

    assertThat(source).isEqualTo("IMDb");
  }

  @Test
  @DisplayName("Should create rating when valid input provided")
  void shouldCreateRatingWhenValidInputProvided() {
    var userId = UUID.randomUUID();
    var savedRating = Rating.builder().source("IMDb").value("8.5").createdBy(userId).build();
    savedRating.setId(UUID.randomUUID());

    when(ratingRepository.save(any(Rating.class))).thenReturn(savedRating);

    String source =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format(
                """
                mutation {
                  addRating(input: { source: "IMDb", value: "8.5", userId: "%s" }) {
                    source
                    value
                  }
                }
                """,
                userId),
            "data.addRating.source");

    assertThat(source).isEqualTo("IMDb");
  }

  @Test
  @DisplayName("Should return error when invalid ID provided")
  void shouldReturnErrorWhenInvalidIdProvided() {
    var result = dgsQueryExecutor.execute("{ rating(id: \"not-a-uuid\") { source } }");

    assertThat(result.getErrors()).isNotEmpty();
    assertThat(result.getErrors().get(0).getMessage()).contains("Invalid ID format");
  }
}
