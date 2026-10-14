package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.media.ArtworkItem;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.fakes.FakeTaskRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fixtures.ArtworkFixture;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Artwork Steps Task Tests")
class ArtworkStepsTaskTest {

  private static final UUID ITEM_ID = UUID.fromString("6f1d7a52-9c1e-4c55-8f0b-0b3a7d1e2c11");

  @Test
  @DisplayName("Should name a task instance after the item's type and id")
  void shouldNameATaskInstanceAfterTheItemsTypeAndId() {
    var item = new ArtworkItem(ITEM_ID, ImageEntityType.PERSON);

    assertThat(ArtworkStepsTask.instanceId(item))
        .isEqualTo("PERSON:6f1d7a52-9c1e-4c55-8f0b-0b3a7d1e2c11");
  }

  @Test
  @DisplayName("Should name the task it creates")
  void shouldNameTheTaskItCreates() {
    var task = ArtworkStepsTask.create("primary-artwork-steps", steps(), completion());

    assertThat(task.getName()).isEqualTo("primary-artwork-steps");
  }

  @Test
  @DisplayName("Should reject a task without its name, steps or completion")
  void shouldRejectATaskWithoutItsNameStepsOrCompletion() {
    var steps = steps();
    var completion = completion();

    assertThatNullPointerException()
        .isThrownBy(() -> ArtworkStepsTask.create(null, steps, completion));
    assertThatNullPointerException()
        .isThrownBy(() -> ArtworkStepsTask.create("primary-artwork-steps", null, completion));
    assertThatNullPointerException()
        .isThrownBy(() -> ArtworkStepsTask.create("primary-artwork-steps", steps, null));
    assertThatNullPointerException().isThrownBy(() -> ArtworkStepsTask.instanceId(null));
  }

  @ParameterizedTest
  @EnumSource(
      value = ImageEntityType.class,
      names = {"MOVIE", "SERIES", "SEASON", "EPISODE"})
  @DisplayName("Should give movie, series, season and episode artwork primary priority")
  void shouldGiveMovieSeriesSeasonAndEpisodeArtworkPrimaryPriority(ImageEntityType itemType) {
    assertThat(ArtworkPriority.of(itemType)).isEqualTo(ArtworkPriority.PRIMARY);
  }

  @ParameterizedTest
  @EnumSource(
      value = ImageEntityType.class,
      names = {"PERSON", "COMPANY"})
  @DisplayName("Should give person and company artwork background priority")
  void shouldGivePersonAndCompanyArtworkBackgroundPriority(ImageEntityType itemType) {
    assertThat(ArtworkPriority.of(itemType)).isEqualTo(ArtworkPriority.BACKGROUND);
  }

  @Test
  @DisplayName("Should reject requested artwork without its item or awaited image types")
  void shouldRejectRequestedArtworkWithoutItsItemOrAwaitedImageTypes() {
    var item = new ArtworkItem(ITEM_ID, ImageEntityType.MOVIE);
    var awaited = Set.of(ImageType.POSTER);

    assertThatNullPointerException().isThrownBy(() -> ArtworkPriority.of(null));
    assertThatNullPointerException().isThrownBy(() -> new RequestedArtwork(null, 1, awaited));
    assertThatNullPointerException().isThrownBy(() -> new RequestedArtwork(item, 1, null));
  }

  private final ArtworkFixture artwork = ArtworkFixture.builder().build();

  private ArtworkSteps steps() {
    return artwork.steps();
  }

  private ArtworkStepsCompletion completion() {
    return ArtworkStepsCompletion.builder()
        .requests(artwork.requests())
        .transactionManager(new FakeTransactionManager())
        .artworkTasks(new FakeTaskRepository())
        .clock(Clock.systemUTC())
        .build();
  }
}
