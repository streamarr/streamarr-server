package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.metadata.Rating;
import com.streamarr.server.fakes.FakeRatingRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Rating Service Tests")
class RatingServiceTest {

  private FakeRatingRepository ratingRepository;
  private RatingService ratingService;

  @BeforeEach
  void setUp() {
    ratingRepository = new FakeRatingRepository();
    ratingService = new RatingService(ratingRepository);
  }

  @Test
  @DisplayName("Should save a rating with the given source and value when adding a rating")
  void shouldSaveRatingWithGivenSourceAndValueWhenAddingRating() {
    var added = ratingService.addRating("IMDb", "8.5");

    assertThat(ratingRepository.findById(added.getId()))
        .get()
        .extracting(Rating::getSource, Rating::getValue)
        .containsExactly("IMDb", "8.5");
  }

  @Test
  @DisplayName("Should return the rating when finding a saved rating by its id")
  void shouldReturnRatingWhenFindingSavedRatingById() {
    ratingRepository.save(Rating.builder().source("IMDb").value("8.5").build());
    var saved = ratingRepository.save(Rating.builder().source("TMDB").value("7.5").build());

    assertThat(ratingService.findById(saved.getId())).containsSame(saved);
  }

  @Test
  @DisplayName("Should return empty when finding a rating that does not exist")
  void shouldReturnEmptyWhenFindingRatingThatDoesNotExist() {
    ratingRepository.save(Rating.builder().source("IMDb").value("8.5").build());

    assertThat(ratingService.findById(UUID.randomUUID())).isEmpty();
  }
}
