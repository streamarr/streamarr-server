package com.streamarr.server.services;

import static com.streamarr.server.fakes.TestImages.createTestImage;
import static com.streamarr.server.fixtures.ImageFixture.imageBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.ImageProperties;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.fakes.FakeItemResultRepository;
import com.streamarr.server.fakes.FakeTmdbHttpService;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.ImageVariantService;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

@Tag("UnitTest")
@DisplayName("Artwork Fetcher Result Recording Tests")
class ArtworkFetcherTest {

  private static final Instant FIRST_ATTEMPT = Instant.parse("2026-09-23T10:00:00Z");
  private static final Instant SECOND_ATTEMPT = Instant.parse("2026-09-23T11:00:00Z");

  private final UUID entityId = UUID.randomUUID();
  private final FakeImageRepository imageRepository = new FakeImageRepository();
  private final FakeItemResultRepository itemResults = new FakeItemResultRepository();
  private final FakeTmdbHttpService imageDownloader = new FakeTmdbHttpService();
  private final FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix());
  private ArtworkFetcher artworkFetcher;

  @BeforeEach
  void setUp() {
    imageDownloader.setImageData(createTestImage(600, 900));
    var imageService =
        new ImageService(
            imageRepository,
            new ImageVariantService(),
            new ImageProperties("/data/images"),
            fileSystem,
            itemResults);
    artworkFetcher =
        new ArtworkFetcher(imageDownloader, imageService, itemResults, new MutexFactoryProvider());
  }

  @Test
  @DisplayName("Should record saved artwork with its source when images are stored")
  void shouldRecordSavedArtworkWithItsSourceWhenImagesAreStored() {
    artworkFetcher.fetch(
        movieArtwork(poster("/poster.jpg"), backdrop("/backdrop.jpg")),
        ImageRefreshMode.PRESERVE,
        FIRST_ATTEMPT);

    assertThat(itemResults.findByItem(entityId, ImageEntityType.MOVIE))
        .containsExactlyInAnyOrder(
            artwork(ImageType.POSTER, new ItemOutcome.Succeeded(), "/poster.jpg"),
            artwork(ImageType.BACKDROP, new ItemOutcome.Succeeded(), "/backdrop.jpg"));
  }

  @Test
  @DisplayName("Should record unavailable artwork when the provider has no required image")
  void shouldRecordUnavailableArtworkWhenTheProviderHasNoRequiredImage() {
    artworkFetcher.fetch(
        movieArtwork(backdrop("/backdrop.jpg")), ImageRefreshMode.PRESERVE, FIRST_ATTEMPT);

    assertThat(itemResults.find(entityId, ItemStep.ARTWORK, ImageType.POSTER))
        .contains(artwork(ImageType.POSTER, new ItemOutcome.Unavailable(), null));
  }

  @Test
  @DisplayName("Should record unavailable artwork when the provider has no person photo")
  void shouldRecordUnavailableArtworkWhenTheProviderHasNoPersonPhoto() {
    var person =
        ArtworkSources.builder()
            .entityId(entityId)
            .entityType(ImageEntityType.PERSON)
            .sources(List.of())
            .build();

    artworkFetcher.fetch(person, ImageRefreshMode.PRESERVE, FIRST_ATTEMPT);

    assertThat(itemResults.find(entityId, ItemStep.ARTWORK, ImageType.PROFILE))
        .map(ItemResult::outcome)
        .contains(new ItemOutcome.Unavailable());
  }

  @Test
  @DisplayName("Should record nothing for artwork that preserve mode skips")
  void shouldRecordNothingForArtworkThatPreserveModeSkips() {
    saveExistingImage(ImageType.POSTER);

    artworkFetcher.fetch(
        movieArtwork(poster("/poster.jpg"), backdrop("/backdrop.jpg")),
        ImageRefreshMode.PRESERVE,
        FIRST_ATTEMPT);

    assertThat(itemResults.find(entityId, ItemStep.ARTWORK, ImageType.POSTER)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should record a failed download with its source when the image cannot be downloaded")
  void shouldRecordAFailedDownloadWithItsSourceWhenTheImageCannotBeDownloaded() {
    imageDownloader.setFailOnPath("/poster.jpg");

    artworkFetcher.fetch(
        movieArtwork(poster("/poster.jpg"), backdrop("/backdrop.jpg")),
        ImageRefreshMode.PRESERVE,
        FIRST_ATTEMPT);

    var poster = itemResults.find(entityId, ItemStep.ARTWORK, ImageType.POSTER).orElseThrow();
    assertThat(poster.sourceKey()).isEqualTo("/poster.jpg");
    assertThat(failureReason(poster)).isEqualTo(ItemFailureReason.DOWNLOAD_FAILED);
  }

  @Test
  @DisplayName("Should record invalid media when the downloaded image cannot be decoded")
  void shouldRecordInvalidMediaWhenTheDownloadedImageCannotBeDecoded() {
    imageDownloader.setImageData("not an image".getBytes());

    artworkFetcher.fetch(
        movieArtwork(poster("/poster.jpg")), ImageRefreshMode.PRESERVE, FIRST_ATTEMPT);

    assertThat(posterFailureReason()).isEqualTo(ItemFailureReason.INVALID_MEDIA);
  }

  @Test
  @DisplayName("Should record a temporary failure when the download is interrupted")
  void shouldRecordATemporaryFailureWhenTheDownloadIsInterrupted() {
    imageDownloader.setInterruptOnPath("/poster.jpg");

    artworkFetcher.fetch(
        movieArtwork(poster("/poster.jpg")), ImageRefreshMode.PRESERVE, FIRST_ATTEMPT);

    assertThat(posterFailureReason()).isEqualTo(ItemFailureReason.TEMPORARY);
  }

  @Test
  @DisplayName("Should record a temporary failure when image storage cannot be written")
  void shouldRecordATemporaryFailureWhenImageStorageCannotBeWritten() throws IOException {
    Files.createDirectories(fileSystem.getPath("/data/images"));
    Files.writeString(fileSystem.getPath("/data/images/movie"), "not a directory");

    artworkFetcher.fetch(
        movieArtwork(poster("/poster.jpg")), ImageRefreshMode.PRESERVE, FIRST_ATTEMPT);

    assertThat(posterFailureReason()).isEqualTo(ItemFailureReason.TEMPORARY);
  }

  @Test
  @DisplayName("Should keep the existing image and record a failure when replacement fails")
  void shouldKeepTheExistingImageAndRecordAFailureWhenReplacementFails() {
    var existing = saveExistingImage(ImageType.POSTER);
    imageRepository.setFailOnReplaceLogicalArtwork(true);

    artworkFetcher.fetch(
        movieArtwork(poster("/new-poster.jpg"), backdrop("/backdrop.jpg")),
        ImageRefreshMode.FORCE_REFRESH,
        FIRST_ATTEMPT);

    assertThat(imageRepository.findById(existing.getId())).isPresent();
    assertThat(posterFailureReason()).isEqualTo(ItemFailureReason.TEMPORARY);
  }

  @Test
  @DisplayName("Should resolve the failure when a preserve-mode retry saves the missing image")
  void shouldResolveTheFailureWhenAPreserveModeRetrySavesTheMissingImage() {
    imageDownloader.setFailOnPath("/poster.jpg");
    artworkFetcher.fetch(
        movieArtwork(poster("/poster.jpg")), ImageRefreshMode.PRESERVE, FIRST_ATTEMPT);
    imageDownloader.setFailOnPath(null);

    artworkFetcher.fetch(
        movieArtwork(poster("/poster.jpg")), ImageRefreshMode.PRESERVE, SECOND_ATTEMPT);

    assertThat(itemResults.find(entityId, ItemStep.ARTWORK, ImageType.POSTER))
        .contains(
            artwork(ImageType.POSTER, new ItemOutcome.Succeeded(), "/poster.jpg").toBuilder()
                .attemptedAt(SECOND_ATTEMPT)
                .build());
  }

  @Test
  @DisplayName("Should keep the newer success when an older attempt fails last")
  void shouldKeepTheNewerSuccessWhenAnOlderAttemptFailsLast() {
    artworkFetcher.fetch(
        movieArtwork(poster("/poster.jpg")), ImageRefreshMode.PRESERVE, SECOND_ATTEMPT);
    imageRepository.deleteAll();
    imageDownloader.setFailOnPath("/poster.jpg");

    artworkFetcher.fetch(
        movieArtwork(poster("/poster.jpg")), ImageRefreshMode.PRESERVE, FIRST_ATTEMPT);

    assertThat(itemResults.find(entityId, ItemStep.ARTWORK, ImageType.POSTER))
        .map(ItemResult::outcome)
        .contains(new ItemOutcome.Succeeded());
  }

  @Test
  @DisplayName("Should record the stored artwork when an older replacement finishes last")
  void shouldRecordTheStoredArtworkWhenAnOlderReplacementFinishesLast() {
    artworkFetcher.fetch(
        movieArtwork(poster("/new.jpg"), backdrop("/backdrop.jpg")),
        ImageRefreshMode.PRESERVE,
        SECOND_ATTEMPT);

    artworkFetcher.fetch(
        movieArtwork(poster("/old.jpg"), backdrop("/backdrop.jpg")),
        ImageRefreshMode.FORCE_REFRESH,
        FIRST_ATTEMPT);

    assertThat(
            imageRepository.findByEntityIdAndEntityTypeAndImageType(
                entityId, ImageEntityType.MOVIE, ImageType.POSTER))
        .extracting(Image::getKey)
        .containsOnly("/old.jpg");
    assertThat(itemResults.find(entityId, ItemStep.ARTWORK, ImageType.POSTER))
        .contains(artwork(ImageType.POSTER, new ItemOutcome.Succeeded(), "/old.jpg"));
  }

  @Test
  @DisplayName("Should report the database error when a result cannot be recorded")
  void shouldReportTheDatabaseErrorWhenAResultCannotBeRecorded() {
    var failure = new DataAccessResourceFailureException("database unavailable");
    itemResults.failRecordsWith(failure);
    var artwork = movieArtwork(poster("/poster.jpg"));

    assertThatThrownBy(
            () -> artworkFetcher.fetch(artwork, ImageRefreshMode.PRESERVE, FIRST_ATTEMPT))
        .isSameAs(failure);
  }

  private ArtworkSources movieArtwork(ImageSource... sources) {
    return ArtworkSources.builder()
        .entityId(entityId)
        .entityType(ImageEntityType.MOVIE)
        .sources(List.of(sources))
        .build();
  }

  private static ImageSource poster(String key) {
    return new TmdbImageSource(ImageType.POSTER, key);
  }

  private static ImageSource backdrop(String key) {
    return new TmdbImageSource(ImageType.BACKDROP, key);
  }

  private ItemResult artwork(ImageType imageType, ItemOutcome outcome, String sourceKey) {
    return ItemResult.builder()
        .itemId(entityId)
        .itemType(ImageEntityType.MOVIE)
        .step(ItemStep.ARTWORK)
        .imageType(imageType)
        .outcome(outcome)
        .sourceKey(sourceKey)
        .attemptedAt(FIRST_ATTEMPT)
        .build();
  }

  private Image saveExistingImage(ImageType imageType) {
    return imageRepository.save(
        imageBuilder(entityId)
            .imageType(imageType)
            .key("/existing.jpg")
            .path("movie/" + entityId + "/" + imageType.name().toLowerCase() + "/small.jpg")
            .build());
  }

  private ItemFailureReason posterFailureReason() {
    return failureReason(
        itemResults.find(entityId, ItemStep.ARTWORK, ImageType.POSTER).orElseThrow());
  }

  private static ItemFailureReason failureReason(ItemResult result) {
    if (result.outcome() instanceof ItemOutcome.Failed(var reason, _)) {
      return reason;
    }

    throw new AssertionError("Expected a failed result but was " + result.outcome());
  }
}
