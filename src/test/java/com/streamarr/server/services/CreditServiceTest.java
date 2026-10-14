package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeCompanyRepository;
import com.streamarr.server.fakes.FakeGenreRepository;
import com.streamarr.server.fakes.FakePersonRepository;
import com.streamarr.server.fixtures.CreditServiceFixture;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Credit Service Tests")
class CreditServiceTest {

  private FakePersonRepository personRepository;
  private FakeGenreRepository genreRepository;
  private FakeCompanyRepository companyRepository;
  private CapturingEventPublisher eventPublisher;
  private CreditService creditService;

  @BeforeEach
  void setUp() {
    personRepository = new FakePersonRepository();
    genreRepository = new FakeGenreRepository();
    companyRepository = new FakeCompanyRepository();
    eventPublisher = new CapturingEventPublisher();
    creditService =
        CreditServiceFixture.creditServiceBuilder()
            .personRepository(personRepository)
            .genreRepository(genreRepository)
            .companyRepository(companyRepository)
            .eventPublisher(eventPublisher)
            .build();
  }

  @Test
  @DisplayName("Should return cast and directors in their listed order when saving credits")
  void shouldReturnCastAndDirectorsInTheirListedOrderWhenSavingCredits() {
    var credits =
        MediaTitleCredits.builder()
            .cast(List.of(person("300", "Third Source Actor"), person("100", "First Source Actor")))
            .directors(
                List.of(person("400", "Fourth Source Director"), person("200", "Second Director")))
            .build();

    var saved = creditService.getOrCreateCredits(requestFor(credits).build());

    assertThat(saved.cast())
        .extracting(Person::getSourceId, Person::getName)
        .containsExactly(tuple("300", "Third Source Actor"), tuple("100", "First Source Actor"));
    assertThat(saved.directors()).extracting(Person::getSourceId).containsExactly("400", "200");
    assertThat(saved.cast()).allSatisfy(person -> assertThat(person.getId()).isNotNull());
    assertThat(personRepository.count()).isEqualTo(4);
  }

  @Test
  @DisplayName("Should return saved genres and studios when saving credits")
  void shouldReturnSavedGenresAndStudiosWhenSavingCredits() {
    var credits =
        MediaTitleCredits.builder()
            .genres(Set.of(genre("878", "Science Fiction"), genre("18", "Drama")))
            .studios(Set.of(company("174", "Studio With Logo")))
            .build();

    var saved = creditService.getOrCreateCredits(requestFor(credits).build());

    assertThat(saved.genres())
        .extracting(Genre::getSourceId)
        .containsExactlyInAnyOrder("878", "18");
    assertThat(saved.genres()).allSatisfy(genre -> assertThat(genre.getId()).isNotNull());
    assertThat(saved.studios()).extracting(Company::getName).containsExactly("Studio With Logo");
    assertThat(companyRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should request person and company artwork with the requested image refresh mode when"
          + " saving credits")
  void shouldRequestPersonAndCompanyArtworkWithTheRequestedImageRefreshModeWhenSavingCredits() {
    var profile = List.<ImageSource>of(new TmdbImageSource(ImageType.PROFILE, "/profile.jpg"));
    var logo = List.<ImageSource>of(new TmdbImageSource(ImageType.LOGO, "/logo.png"));
    var credits =
        MediaTitleCredits.builder()
            .cast(List.of(person("100", "Actor With Profile"), person("200", "Actor Without")))
            .studios(Set.of(company("174", "Studio With Logo")))
            .build();

    var saved =
        creditService.getOrCreateCredits(
            requestFor(credits)
                .personImageSources(Map.of("100", profile))
                .companyImageSources(Map.of("174", logo))
                .imageRefreshMode(ImageRefreshMode.FORCE_REFRESH)
                .build());

    assertThat(eventPublisher.getEventsOfType(MetadataEnrichedEvent.class))
        .containsExactlyInAnyOrder(
            new MetadataEnrichedEvent(
                saved.cast().getFirst().getId(),
                ImageEntityType.PERSON,
                profile,
                ImageRefreshMode.FORCE_REFRESH),
            new MetadataEnrichedEvent(
                saved.studios().iterator().next().getId(),
                ImageEntityType.COMPANY,
                logo,
                ImageRefreshMode.FORCE_REFRESH));
  }

  @Test
  @DisplayName("Should return no credits when the media title has none")
  void shouldReturnNoCreditsWhenTheMediaTitleHasNone() {
    var saved =
        creditService.getOrCreateCredits(
            requestFor(new MediaTitleCredits(null, null, null, null)).build());

    assertThat(saved.cast()).isEmpty();
    assertThat(saved.directors()).isEmpty();
    assertThat(saved.genres()).isEmpty();
    assertThat(saved.studios()).isEmpty();
    assertThat(eventPublisher.getEventsOfType(MetadataEnrichedEvent.class)).isEmpty();
  }

  @Test
  @DisplayName("Should reject genres before saving any when one genre has no source id")
  void shouldRejectGenresBeforeSavingAnyWhenOneGenreHasNoSourceId() {
    var genres = new LinkedHashSet<Genre>();
    genres.add(genre("18", "Drama"));
    genres.add(genre(null, "Unidentified Genre"));
    var request = requestFor(MediaTitleCredits.builder().genres(genres).build()).build();

    assertThatThrownBy(() -> creditService.getOrCreateCredits(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Genre sourceId must not be null");
    assertThat(genreRepository.count()).isZero();
  }

  @Test
  @DisplayName("Should reject studios before saving any when one studio has no source id")
  void shouldRejectStudiosBeforeSavingAnyWhenOneStudioHasNoSourceId() {
    var studios = new LinkedHashSet<Company>();
    studios.add(company("174", "Identified Studio"));
    studios.add(company(null, "Unidentified Studio"));
    var request = requestFor(MediaTitleCredits.builder().studios(studios).build()).build();

    assertThatThrownBy(() -> creditService.getOrCreateCredits(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Company sourceId must not be null");
    assertThat(companyRepository.count()).isZero();
  }

  private static MediaTitleCreditsRequest.MediaTitleCreditsRequestBuilder requestFor(
      MediaTitleCredits credits) {
    return MediaTitleCreditsRequest.builder()
        .credits(credits)
        .imageRefreshMode(ImageRefreshMode.PRESERVE);
  }

  private static Person person(String sourceId, String name) {
    return Person.builder().sourceId(sourceId).name(name).build();
  }

  private static Genre genre(String sourceId, String name) {
    return Genre.builder().sourceId(sourceId).name(name).build();
  }

  private static Company company(String sourceId, String name) {
    return Company.builder().sourceId(sourceId).name(name).build();
  }
}
