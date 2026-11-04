package com.streamarr.server.repositories.language;

import static com.streamarr.server.jooq.generated.tables.LanguageOverride.LANGUAGE_OVERRIDE;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitBlockedBackendPid;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static com.streamarr.server.support.TestFixtureWork.newExecutorRunningAsTestFixtureActor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.language.Language;
import com.streamarr.server.domain.language.LanguageOverride;
import com.streamarr.server.domain.language.LanguageOverrideChange;
import com.streamarr.server.domain.language.OverrideFieldChange;
import com.streamarr.server.domain.language.OverrideScope;
import com.streamarr.server.domain.language.PreferenceSource;
import com.streamarr.server.domain.language.ProfileLanguagePreferences;
import com.streamarr.server.domain.language.SubtitlePreferenceMode;
import com.streamarr.server.domain.language.TitleKind;
import com.streamarr.server.domain.language.TitleLineage;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.language.LanguagePreferenceService;
import com.streamarr.server.services.language.SetLanguageOverrideCommand;
import com.streamarr.server.support.AuthTestSupport;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Language Preference Repository Integration Tests")
class LanguagePreferenceRepositoryIT extends AbstractIntegrationTest {

  @Autowired private LanguagePreferenceRepository repository;
  @Autowired private LanguagePreferenceService service;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private JwtDecoder jwtDecoder;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;
  @Autowired private TransactionTemplate transactions;
  @Autowired private DataSource dataSource;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity viewerIdentity;
  private AuthTestSupport.TestIdentity housemateIdentity;
  private Profile viewer;
  private Profile housemate;
  private Series series;
  private Season season;
  private Episode episode;

  @BeforeEach
  void saveTwoProfilesOfOneHouseholdAndOneEpisode() {
    viewerIdentity = authTestSupport.createIdentity();
    housemateIdentity = authTestSupport.createHouseholdMemberIdentity(viewerIdentity);
    viewer = viewerIdentity.profile();
    housemate = housemateIdentity.profile();

    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    series =
        seriesRepository.saveAndFlush(
            Series.builder().title("Series").titleSort("Series").library(library).build());
    season =
        seasonRepository.saveAndFlush(
            Season.builder()
                .title("Season 1")
                .seasonNumber(1)
                .series(series)
                .library(library)
                .build());
    episode =
        episodeRepository.saveAndFlush(
            Episode.builder()
                .title("Pilot")
                .episodeNumber(1)
                .season(season)
                .library(library)
                .build());
  }

  @Test
  @DisplayName("Should read the Profile's preferences back in order when they are stored")
  void shouldReadTheProfilesPreferencesBackInOrderWhenTheyAreStored() {
    var wanted =
        ProfileLanguagePreferences.builder()
            .audioLanguages(List.of(Language.PT_BR, Language.JA, Language.EN))
            .subtitleLanguage(Optional.of(Language.ZH_HANT))
            .subtitleMode(SubtitlePreferenceMode.FOREIGN_AUDIO)
            .build();

    var stored =
        transactions.execute(_ -> repository.trySetProfilePreferences(viewer.getId(), wanted));

    assertThat(stored).isTrue();
    assertThat(repository.findProfilePreferences(viewer.getId())).contains(wanted);
    assertThat(repository.findProfilePreferences(housemate.getId()))
        .contains(ProfileLanguagePreferences.defaults());
  }

  @Test
  @DisplayName("Should store nothing when no Profile has the id")
  void shouldStoreNothingWhenNoProfileHasTheId() {
    var missing = UUID.randomUUID();

    var stored =
        transactions.execute(
            _ ->
                repository.trySetProfilePreferences(
                    missing, ProfileLanguagePreferences.defaults()));

    assertThat(stored).isFalse();
    assertThat(repository.findProfilePreferences(missing)).isEmpty();
  }

  @Test
  @DisplayName("Should refuse six audio languages when the database checks the Profile's list")
  void shouldRefuseSixAudioLanguagesWhenTheDatabaseChecksTheProfilesList() {
    var six =
        ProfileLanguagePreferences.builder()
            .audioLanguages(
                List.of(
                    Language.EN, Language.FR, Language.DE, Language.ES, Language.IT, Language.JA))
            .build();

    assertThatThrownBy(
            () ->
                transactions.execute(_ -> repository.trySetProfilePreferences(viewer.getId(), six)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("profile_audio_languages_at_most_five");
  }

  @Test
  @DisplayName("Should keep each Profile's override apart when two Profiles override one series")
  void shouldKeepEachProfilesOverrideApartWhenTwoProfilesOverrideOneSeries() {
    service.setLanguageOverride(
        watchingAs(viewerIdentity),
        SetLanguageOverrideCommand.builder()
            .itemId(series.getId())
            .change(audio(Language.JA))
            .build());
    service.setLanguageOverride(
        watchingAs(housemateIdentity),
        SetLanguageOverrideCommand.builder()
            .itemId(series.getId())
            .change(audio(Language.EN))
            .build());

    var viewerPreference =
        service.languagePreferences(viewer.getId(), List.of(episode.getId())).get(episode.getId());
    var housematePreference =
        service
            .languagePreferences(housemate.getId(), List.of(episode.getId()))
            .get(episode.getId());

    assertThat(viewerPreference.audioLanguages()).containsExactly(Language.JA);
    assertThat(viewerPreference.audioSource()).isEqualTo(PreferenceSource.SERIES);
    assertThat(housematePreference.audioLanguages()).containsExactly(Language.EN);
  }

  @Test
  @DisplayName("Should keep the fields a change leaves out when the override is stored again")
  void shouldKeepTheFieldsAChangeLeavesOutWhenTheOverrideIsStoredAgain() {
    change(
        viewer,
        seriesScope(),
        LanguageOverrideChange.builder()
            .audioLanguage(OverrideFieldChange.replaceWith(Optional.of(Language.JA)))
            .subtitleMode(OverrideFieldChange.replaceWith(Optional.of(SubtitlePreferenceMode.OFF)))
            .build());

    change(viewer, seriesScope(), audio(Language.KO));

    assertThat(overridesOf(viewer))
        .containsEntry(
            series.getId(),
            LanguageOverride.builder()
                .audioLanguage(Optional.of(Language.KO))
                .subtitleMode(Optional.of(SubtitlePreferenceMode.OFF))
                .build());
  }

  @Test
  @DisplayName("Should remove the override when a change leaves every field inheriting")
  void shouldRemoveTheOverrideWhenAChangeLeavesEveryFieldInheriting() {
    change(viewer, seriesScope(), audio(Language.JA));

    change(
        viewer,
        seriesScope(),
        LanguageOverrideChange.builder()
            .audioLanguage(OverrideFieldChange.replaceWith(Optional.empty()))
            .build());

    assertThat(overridesOf(viewer)).isEmpty();
    assertThat(storedRowsOf(viewer)).isZero();
  }

  @Test
  @DisplayName("Should store no row when a change keeps every field of a missing override")
  void shouldStoreNoRowWhenAChangeKeepsEveryFieldOfAMissingOverride() {
    change(viewer, seriesScope(), LanguageOverrideChange.builder().build());

    assertThat(storedRowsOf(viewer)).isZero();
  }

  @Test
  @DisplayName("Should delete only the named title's override when an override is cleared")
  void shouldDeleteOnlyTheNamedTitlesOverrideWhenAnOverrideIsCleared() {
    change(viewer, seriesScope(), audio(Language.JA));
    change(viewer, new OverrideScope(TitleKind.EPISODE, episode.getId()), audio(Language.FR));
    change(housemate, new OverrideScope(TitleKind.EPISODE, episode.getId()), audio(Language.DE));

    transactions.executeWithoutResult(
        _ ->
            repository.deleteOverride(
                viewer.getId(), new OverrideScope(TitleKind.EPISODE, episode.getId())));

    assertThat(overridesOf(viewer)).containsOnlyKeys(series.getId());
    assertThat(overridesOf(housemate)).containsOnlyKeys(episode.getId());
  }

  @Test
  @DisplayName("Should give each title its scopes, most specific first, when lineages are read")
  void shouldGiveEachTitleItsScopesMostSpecificFirstWhenLineagesAreRead() {
    var movie = saveMovie();

    var lineages =
        repository.findLineages(
            List.of(
                episode.getId(), season.getId(), series.getId(), movie.getId(), UUID.randomUUID()));

    var seriesScope = seriesScope();
    var seasonScope = new OverrideScope(TitleKind.SEASON, season.getId());
    assertThat(lineages)
        .hasSize(4)
        .containsEntry(
            episode.getId(),
            new TitleLineage(
                List.of(
                    new OverrideScope(TitleKind.EPISODE, episode.getId()),
                    seasonScope,
                    seriesScope)))
        .containsEntry(season.getId(), new TitleLineage(List.of(seasonScope, seriesScope)))
        .containsEntry(series.getId(), new TitleLineage(List.of(seriesScope)))
        .containsEntry(
            movie.getId(),
            new TitleLineage(List.of(new OverrideScope(TitleKind.MOVIE, movie.getId()))));
  }

  @Test
  @DisplayName("Should remove the episode's overrides when the episode is deleted")
  void shouldRemoveTheEpisodesOverridesWhenTheEpisodeIsDeleted() {
    change(viewer, new OverrideScope(TitleKind.EPISODE, episode.getId()), audio(Language.FR));

    episodeRepository.deleteById(episode.getId());
    episodeRepository.flush();

    assertThat(storedRowsOf(viewer)).isZero();
  }

  @Test
  @DisplayName("Should keep both fields when two changes to one new override run at once")
  void shouldKeepBothFieldsWhenTwoChangesToOneNewOverrideRunAtOnce() throws Exception {
    var firstPid = new AtomicInteger();
    var firstWritten = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    try (var executor = newExecutorRunningAsTestFixtureActor();
        var observer = dataSource.getConnection()) {
      var first =
          executor.submit(
              () ->
                  transactions.executeWithoutResult(
                      _ -> {
                        repository.changeOverride(
                            viewer.getId(), seriesScope(), audio(Language.JA));
                        firstPid.set(
                            dsl.select(DSL.field("pg_backend_pid()", Integer.class))
                                .fetchSingle()
                                .value1());
                        firstWritten.countDown();
                        awaitLatch(releaseFirst);
                      }));
      awaitLatch(firstWritten);
      var second =
          executor.submit(
              () ->
                  transactions.executeWithoutResult(
                      _ ->
                          repository.changeOverride(
                              viewer.getId(),
                              seriesScope(),
                              LanguageOverrideChange.builder()
                                  .subtitleLanguage(
                                      OverrideFieldChange.replaceWith(Optional.of(Language.EN)))
                                  .build())));

      // The second upsert waits for the first transaction's uncommitted row before it updates it.
      awaitBlockedBackendPid(observer, firstPid.get(), "transactionid");
      releaseFirst.countDown();
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
    }

    assertThat(overridesOf(viewer))
        .containsEntry(
            series.getId(),
            LanguageOverride.builder()
                .audioLanguage(Optional.of(Language.JA))
                .subtitleLanguage(Optional.of(Language.EN))
                .build());
  }

  // Deleting either identity deletes the whole Household with both Profiles and their overrides.
  @AfterEach
  void deleteTheHousehold() {
    authTestSupport.deleteIdentity(viewerIdentity);
  }

  private AuthenticatedIdentity watchingAs(AuthTestSupport.TestIdentity identity) {
    return AuthenticatedIdentity.fromJwt(
        jwtDecoder.decode(authTestSupport.profileBearer(identity)));
  }

  private Movie saveMovie() {
    Library library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    return movieRepository.saveAndFlush(
        Movie.builder().title("Movie").titleSort("Movie").library(library).build());
  }

  private OverrideScope seriesScope() {
    return new OverrideScope(TitleKind.SERIES, series.getId());
  }

  private static LanguageOverrideChange audio(Language language) {
    return LanguageOverrideChange.builder()
        .audioLanguage(OverrideFieldChange.replaceWith(Optional.of(language)))
        .build();
  }

  private void change(Profile profile, OverrideScope scope, LanguageOverrideChange change) {
    transactions.executeWithoutResult(
        _ -> repository.changeOverride(profile.getId(), scope, change));
  }

  private Map<UUID, LanguageOverride> overridesOf(Profile profile) {
    return repository.findOverrides(
        profile.getId(), List.of(series.getId(), season.getId(), episode.getId()));
  }

  private int storedRowsOf(Profile profile) {
    return dsl.fetchCount(LANGUAGE_OVERRIDE, LANGUAGE_OVERRIDE.PROFILE_ID.eq(profile.getId()));
  }
}
