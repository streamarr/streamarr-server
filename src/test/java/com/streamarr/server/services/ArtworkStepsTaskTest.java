package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.media.ArtworkTarget;
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

  private static final UUID TARGET_ID = UUID.fromString("6f1d7a52-9c1e-4c55-8f0b-0b3a7d1e2c11");

  @Test
  @DisplayName(
      "Should name a task instance after the target's type and id when a target is scheduled")
  void shouldNameATaskInstanceAfterTheTargetsTypeAndIdWhenATargetIsScheduled() {
    var target = new ArtworkTarget(TARGET_ID, ImageEntityType.PERSON);

    assertThat(ArtworkStepsTask.instanceId(target))
        .isEqualTo("PERSON:6f1d7a52-9c1e-4c55-8f0b-0b3a7d1e2c11");
  }

  @Test
  @DisplayName("Should name the task when it is created")
  void shouldNameTheTaskWhenItIsCreated() {
    var task = ArtworkStepsTask.create("primary-artwork-steps", stepsRunner(), completion());

    assertThat(task.getName()).isEqualTo("primary-artwork-steps");
  }

  @Test
  @DisplayName("Should reject a task when its name, steps or completion is missing")
  void shouldRejectATaskWhenItsNameStepsOrCompletionIsMissing() {
    var stepsRunner = stepsRunner();
    var completion = completion();

    assertThatNullPointerException()
        .isThrownBy(() -> ArtworkStepsTask.create(null, stepsRunner, completion));
    assertThatNullPointerException()
        .isThrownBy(() -> ArtworkStepsTask.create("primary-artwork-steps", null, completion));
    assertThatNullPointerException()
        .isThrownBy(() -> ArtworkStepsTask.create("primary-artwork-steps", stepsRunner, null));
    assertThatNullPointerException().isThrownBy(() -> ArtworkStepsTask.instanceId(null));
  }

  @ParameterizedTest
  @EnumSource(
      value = ImageEntityType.class,
      names = {"MOVIE", "SERIES", "SEASON", "EPISODE"})
  @DisplayName("Should give primary priority when the target is a movie, series, season or episode")
  void shouldGivePrimaryPriorityWhenTheTargetIsAMovieSeriesSeasonOrEpisode(
      ImageEntityType targetType) {
    assertThat(ArtworkPriority.of(targetType)).isEqualTo(ArtworkPriority.PRIMARY);
  }

  @ParameterizedTest
  @EnumSource(
      value = ImageEntityType.class,
      names = {"PERSON", "COMPANY"})
  @DisplayName("Should give background priority when the target is a person or company")
  void shouldGiveBackgroundPriorityWhenTheTargetIsAPersonOrCompany(ImageEntityType targetType) {
    assertThat(ArtworkPriority.of(targetType)).isEqualTo(ArtworkPriority.BACKGROUND);
  }

  @Test
  @DisplayName("Should reject requested artwork when its target or awaited image types are missing")
  void shouldRejectRequestedArtworkWhenItsTargetOrAwaitedImageTypesAreMissing() {
    var target = new ArtworkTarget(TARGET_ID, ImageEntityType.MOVIE);
    var awaited = Set.of(ImageType.POSTER);

    assertThatNullPointerException().isThrownBy(() -> ArtworkPriority.of(null));
    assertThatNullPointerException().isThrownBy(() -> new RequestedArtwork(null, 1, awaited));
    assertThatNullPointerException().isThrownBy(() -> new RequestedArtwork(target, 1, null));
  }

  private final ArtworkFixture artwork = ArtworkFixture.builder().build();

  private ArtworkStepsRunner stepsRunner() {
    return artwork.stepsRunner();
  }

  private ArtworkStepsTaskCompletion completion() {
    return ArtworkStepsTaskCompletion.builder()
        .requests(artwork.requests())
        .transactionManager(new FakeTransactionManager())
        .artworkTasks(new FakeTaskRepository())
        .clock(Clock.systemUTC())
        .build();
  }
}
