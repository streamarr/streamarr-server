package com.streamarr.server.services;

import static com.streamarr.server.fixtures.ImageFixture.imageBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.services.ArtworkPlan.PlannedImage.DownloadImage;
import com.streamarr.server.services.ArtworkPlan.PlannedImage.KeepStoredImage;
import com.streamarr.server.services.ArtworkPlan.PlannedImage.ReportUnavailable;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Artwork plan")
class ArtworkPlanTest {

  private static final ImageSource POSTER = new TmdbImageSource(ImageType.POSTER, "/poster.jpg");

  private final UUID movieId = UUID.randomUUID();

  @ParameterizedTest
  @EnumSource(ImageRefreshMode.class)
  @DisplayName("Should download every source when the movie stores no images")
  void shouldDownloadEverySourceWhenTheMovieStoresNoImages(ImageRefreshMode refreshMode) {
    var plan = ArtworkPlan.of(movieArtwork(POSTER), Map.of(), refreshMode);

    assertThat(plan.images())
        .containsExactly(
            new ReportUnavailable(ImageType.BACKDROP), new DownloadImage(POSTER, false));
  }

  @Test
  @DisplayName("Should keep stored artwork when preserve finds an image of that type")
  void shouldKeepStoredArtworkWhenPreserveFindsAnImageOfThatType() {
    var plan =
        ArtworkPlan.of(movieArtwork(POSTER), storedPoster("/other.jpg"), ImageRefreshMode.PRESERVE);

    assertThat(plan.images()).contains(new KeepStoredImage(ImageType.POSTER));
  }

  @Test
  @DisplayName("Should keep stored artwork when refresh if changed finds the same key")
  void shouldKeepStoredArtworkWhenRefreshIfChangedFindsTheSameKey() {
    var plan =
        ArtworkPlan.of(
            movieArtwork(POSTER), storedPoster("/poster.jpg"), ImageRefreshMode.REFRESH_IF_CHANGED);

    assertThat(plan.images()).contains(new KeepStoredImage(ImageType.POSTER));
  }

  @Test
  @DisplayName("Should download a replacement when refresh if changed finds a different key")
  void shouldDownloadAReplacementWhenRefreshIfChangedFindsADifferentKey() {
    var plan =
        ArtworkPlan.of(
            movieArtwork(POSTER), storedPoster("/other.jpg"), ImageRefreshMode.REFRESH_IF_CHANGED);

    assertThat(plan.images()).contains(new DownloadImage(POSTER, true));
  }

  @Test
  @DisplayName(
      "Should download a replacement when refresh if changed finds a stored image without a key")
  void shouldDownloadAReplacementWhenRefreshIfChangedFindsAStoredImageWithoutAKey() {
    var plan =
        ArtworkPlan.of(
            movieArtwork(POSTER), storedPoster(null), ImageRefreshMode.REFRESH_IF_CHANGED);

    assertThat(plan.images()).contains(new DownloadImage(POSTER, true));
  }

  @Test
  @DisplayName("Should download a replacement when a forced refresh finds the same key")
  void shouldDownloadAReplacementWhenAForcedRefreshFindsTheSameKey() {
    var plan =
        ArtworkPlan.of(
            movieArtwork(POSTER), storedPoster("/poster.jpg"), ImageRefreshMode.FORCE_REFRESH);

    assertThat(plan.images()).contains(new DownloadImage(POSTER, true));
  }

  @Test
  @DisplayName("Should keep a stored image without a source when preserve plans it")
  void shouldKeepAStoredImageWithoutASourceWhenPreservePlansIt() {
    var plan =
        ArtworkPlan.of(movieArtwork(), storedPoster("/poster.jpg"), ImageRefreshMode.PRESERVE);

    assertThat(plan.images())
        .containsExactly(
            new KeepStoredImage(ImageType.POSTER), new ReportUnavailable(ImageType.BACKDROP));
  }

  @Test
  @DisplayName("Should mark unavailable when a forced refresh finds no provider image")
  void shouldMarkUnavailableWhenAForcedRefreshFindsNoProviderImage() {
    var plan =
        ArtworkPlan.of(movieArtwork(), storedPoster("/poster.jpg"), ImageRefreshMode.FORCE_REFRESH);

    assertThat(plan.images())
        .containsExactly(
            new ReportUnavailable(ImageType.POSTER), new ReportUnavailable(ImageType.BACKDROP));
  }

  @Test
  @DisplayName("Should name the planned image type when a download is planned")
  void shouldNameThePlannedImageTypeWhenADownloadIsPlanned() {
    assertThat(new DownloadImage(POSTER, false).imageType()).isEqualTo(ImageType.POSTER);
  }

  @Test
  @DisplayName("Should reject a plan when its artwork, stored images or refresh mode is missing")
  void shouldRejectAPlanWhenItsArtworkStoredImagesOrRefreshModeIsMissing() {
    var artwork = movieArtwork(POSTER);
    var noneStored = Map.<ImageType, List<Image>>of();

    assertThatNullPointerException()
        .isThrownBy(() -> ArtworkPlan.of(null, noneStored, ImageRefreshMode.PRESERVE));
    assertThatNullPointerException()
        .isThrownBy(() -> ArtworkPlan.of(artwork, null, ImageRefreshMode.PRESERVE));
    assertThatNullPointerException().isThrownBy(() -> ArtworkPlan.of(artwork, noneStored, null));
    assertThatNullPointerException().isThrownBy(() -> new ArtworkPlan(null));
  }

  private ArtworkSources movieArtwork(ImageSource... sources) {
    return ArtworkSources.builder()
        .targetId(movieId)
        .targetType(ImageEntityType.MOVIE)
        .sources(List.of(sources))
        .build();
  }

  private Map<ImageType, List<Image>> storedPoster(String key) {
    return Map.of(ImageType.POSTER, List.of(imageBuilder(movieId).key(key).build()));
  }
}
