package com.streamarr.server.services;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.streamarr.server.fakes.TestImages.createTestImage;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractWireMockIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Required Artwork Integration Tests")
class RequiredArtworkIT extends AbstractWireMockIntegrationTest {

  private static final long BOUND_SECONDS = 10;

  @Autowired private ArtworkService artworkService;
  @Autowired private MovieService movieService;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PersonRepository personRepository;

  private Library library;

  @BeforeEach
  void setUp() {
    wireMock.resetAll();
    wireMock.stubFor(
        get(urlPathEqualTo("/poster.jpg"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "image/jpeg")
                    .withBody(createTestImage(600, 900))));
    library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
  }

  @Test
  @DisplayName("Should commit image records before the run reports required artwork finished")
  void shouldCommitImageRecordsBeforeRunReportsRequiredArtworkFinished() throws Exception {
    var artworkRun = artworkService.openRun("scan", ImageRefreshMode.PRESERVE);

    var movie = movieService.createMovieWithAssociations(movieMetadata(), mediaFile(), artworkRun);
    artworkRun.close();
    var summary = artworkRun.completion().get(BOUND_SECONDS, TimeUnit.SECONDS);

    assertThat(summary.counts()).isEqualTo(ArtworkCounts.builder().saved(1).unavailable(1).build());
    assertThat(committedImageRows(movie.getId())).isEqualTo(ImageSize.values().length);
  }

  @Test
  @DisplayName("Should withdraw required artwork when the owning transaction rolls back")
  void shouldWithdrawRequiredArtworkWhenOwningTransactionRollsBack() throws Exception {
    var artworkRun = artworkService.openRun("scan", ImageRefreshMode.PRESERVE);
    var mediaFile = mediaFile();

    var movie =
        transactionTemplate.execute(
            status -> {
              status.setRollbackOnly();
              return movieService.createMovieWithAssociations(
                  movieMetadata(), mediaFile, artworkRun);
            });
    artworkRun.close();
    var summary = artworkRun.completion().get(BOUND_SECONDS, TimeUnit.SECONDS);

    assertThat(summary.counts()).isEqualTo(ArtworkCounts.builder().build());
    assertThat(committedImageRows(movie.getId())).isZero();
    assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo("/poster.jpg")))).isEmpty();
  }

  @Test
  @DisplayName("Should name the System actor as creator when required artwork saves images")
  void shouldNameSystemActorAsCreatorWhenRequiredArtworkSavesImages() throws Exception {
    var artworkRun = artworkService.openRun("scan", ImageRefreshMode.PRESERVE);

    var movie = movieService.createMovieWithAssociations(movieMetadata(), mediaFile(), artworkRun);
    artworkRun.close();
    artworkRun.completion().get(BOUND_SECONDS, TimeUnit.SECONDS);

    assertThat(imageCreators(movie.getId()))
        .hasSize(ImageSize.values().length)
        .containsOnly(SystemActor.ID);
  }

  @Test
  @DisplayName("Should name the System actor as creator when background artwork saves images")
  void shouldNameSystemActorAsCreatorWhenBackgroundArtworkSavesImages() throws Exception {
    var person =
        personRepository.saveAndFlush(
            Person.builder().name("Artwork Person").sourceId(UUID.randomUUID().toString()).build());
    var artwork =
        ArtworkSources.builder()
            .entityId(person.getId())
            .entityType(ImageEntityType.PERSON)
            .sources(List.of(new TmdbImageSource(ImageType.PROFILE, "/poster.jpg")))
            .build();

    artworkService
        .fetchSecondary(artwork, ImageRefreshMode.PRESERVE)
        .get(BOUND_SECONDS, TimeUnit.SECONDS);

    assertThat(imageCreators(person.getId()))
        .hasSize(ImageSize.values().length)
        .containsOnly(SystemActor.ID);
  }

  private MetadataResult<Movie> movieMetadata() {
    return new MetadataResult<>(
        Movie.builder().title("Artwork Movie").titleSort("artwork movie").library(library).build(),
        List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")),
        Map.of(),
        Map.of());
  }

  private MediaFile mediaFile() {
    return mediaFileRepository.save(
        MediaFile.builder()
            .libraryId(library.getId())
            .status(MediaFileStatus.UNMATCHED)
            .filename("artwork.mkv")
            .filepathUri("file:///library/" + UUID.randomUUID() + "/artwork.mkv")
            .build());
  }

  private List<UUID> imageCreators(UUID entityId) {
    return jdbcTemplate.queryForList(
        "SELECT created_by FROM image WHERE entity_id = ?", UUID.class, entityId);
  }

  private int committedImageRows(UUID entityId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM image WHERE entity_id = ?", Integer.class, entityId);
  }
}
