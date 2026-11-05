package com.streamarr.server.services.language;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountScopedBuilder;
import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.profileScopedBuilder;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildEpisode;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildMovie;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeason;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeries;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.language.Language;
import com.streamarr.server.domain.language.LanguageOverrideChange;
import com.streamarr.server.domain.language.LanguagePreference;
import com.streamarr.server.domain.language.OverrideFieldChange;
import com.streamarr.server.domain.language.PreferenceSource;
import com.streamarr.server.domain.language.ProfileLanguagePreferences;
import com.streamarr.server.domain.language.SubtitlePreferenceMode;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeLanguagePreferenceRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeSeriesRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.Outcome;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

@Tag("UnitTest")
@DisplayName("Language Preference Service Tests")
class LanguagePreferenceServiceTest {

  private final FakeMovieRepository movies = new FakeMovieRepository();
  private final FakeSeriesRepository seriesRepository = new FakeSeriesRepository();
  private final FakeSeasonRepository seasons = new FakeSeasonRepository();
  private final FakeEpisodeRepository episodes = new FakeEpisodeRepository();
  private final FakeLanguagePreferenceRepository repository =
      new FakeLanguagePreferenceRepository(movies, seriesRepository, seasons, episodes);
  private final AuthenticatedIdentity viewer = profileScopedBuilder().build();
  private final FakeAuthorizationService authorization = new FakeAuthorizationService(viewer);
  private final LanguagePreferenceService service =
      new LanguagePreferenceService(
          authorization, repository, movies, seriesRepository, seasons, episodes);

  private Series series;
  private Season season;
  private Episode episode;

  @BeforeEach
  void saveOneEpisodeOfOneSeries() {
    series = seriesRepository.save(buildSeries("Series"));
    var unsavedSeason = buildSeason("Season 1", 1);
    unsavedSeason.setSeries(series);
    season = seasons.save(unsavedSeason);
    var unsavedEpisode = buildEpisode("Episode 1", 1);
    unsavedEpisode.setSeason(season);
    episode = episodes.save(unsavedEpisode);
  }

  @Nested
  @DisplayName("Profile Preferences")
  class ProfilePreferences {

    @Test
    @DisplayName("Should return the defaults when the Profile has set no preferences")
    void shouldReturnTheDefaultsWhenTheProfileHasSetNoPreferences() {
      var preferences = service.profilePreferences(viewer).orElseThrow();

      assertThat(preferences.audioLanguages()).isEmpty();
      assertThat(preferences.subtitleLanguage()).isEmpty();
      assertThat(preferences.subtitleMode()).isEqualTo(SubtitlePreferenceMode.FORCED);
    }

    @Test
    @DisplayName("Should return no preferences when no Profile is selected")
    void shouldReturnNoPreferencesWhenNoProfileIsSelected() {
      assertThat(service.profilePreferences(accountScopedBuilder().build())).isEmpty();
    }

    @Test
    @DisplayName("Should keep the audio languages in order when the Profile sets them")
    void shouldKeepTheAudioLanguagesInOrderWhenTheProfileSetsThem() {
      var wanted =
          ProfileLanguagePreferences.builder()
              .audioLanguages(List.of(Language.JA, Language.EN, Language.FR))
              .subtitleLanguage(Optional.of(Language.EN))
              .subtitleMode(SubtitlePreferenceMode.ALWAYS)
              .build();

      var outcome = service.setProfilePreferences(viewer, wanted);

      assertThat(outcome).isEqualTo(Outcome.accepted(wanted));
      assertThat(service.profilePreferences(viewer)).contains(wanted);
    }

    @Test
    @DisplayName("Should reject the list and keep the stored one when it names six languages")
    void shouldRejectTheListAndKeepTheStoredOneWhenItNamesSixLanguages() {
      var six =
          List.of(Language.EN, Language.FR, Language.DE, Language.ES, Language.IT, Language.JA);

      var outcome =
          service.setProfilePreferences(
              viewer, ProfileLanguagePreferences.builder().audioLanguages(six).build());

      assertThat(outcome)
          .isEqualTo(
              Outcome.rejected(new SetLanguagePreferencesRejection.TooManyAudioLanguages(5)));
      assertThat(service.profilePreferences(viewer).orElseThrow().audioLanguages()).isEmpty();
    }

    @Test
    @DisplayName("Should accept five languages when the list names exactly the maximum")
    void shouldAcceptFiveLanguagesWhenTheListNamesExactlyTheMaximum() {
      var five = List.of(Language.EN, Language.FR, Language.DE, Language.ES, Language.IT);

      service.setProfilePreferences(
          viewer, ProfileLanguagePreferences.builder().audioLanguages(five).build());

      assertThat(service.profilePreferences(viewer).orElseThrow().audioLanguages())
          .containsExactlyElementsOf(five);
    }

    @Test
    @DisplayName("Should reject the list when it names one language twice")
    void shouldRejectTheListWhenItNamesOneLanguageTwice() {
      var outcome =
          service.setProfilePreferences(
              viewer,
              ProfileLanguagePreferences.builder()
                  .audioLanguages(List.of(Language.EN, Language.FR, Language.EN))
                  .build());

      assertThat(outcome)
          .isEqualTo(
              Outcome.rejected(
                  new SetLanguagePreferencesRejection.RepeatedAudioLanguage(Language.EN, 2)));
      assertThat(service.profilePreferences(viewer).orElseThrow().audioLanguages()).isEmpty();
    }

    @Test
    @DisplayName("Should require a Profile when the selected Profile was deleted before the write")
    void shouldRequireAProfileWhenTheSelectedProfileWasDeletedBeforeTheWrite() {
      repository.deleteProfile(viewer.profileId());
      var wanted =
          ProfileLanguagePreferences.builder().audioLanguages(List.of(Language.JA)).build();

      assertThatThrownBy(() -> service.setProfilePreferences(viewer, wanted))
          .isInstanceOf(ProfileRequiredException.class);
    }

    @Test
    @DisplayName("Should refuse and store nothing when the viewer may not set the preferences")
    void shouldRefuseAndStoreNothingWhenTheViewerMayNotSetThePreferences() {
      allowOnlyLanguagePreferencesOf(UUID.randomUUID());
      var wanted =
          ProfileLanguagePreferences.builder().audioLanguages(List.of(Language.JA)).build();

      assertThatThrownBy(() -> service.setProfilePreferences(viewer, wanted))
          .isInstanceOf(AccessDeniedException.class);
      assertThat(repository.findProfilePreferences(viewer.profileId()).orElseThrow())
          .isEqualTo(ProfileLanguagePreferences.defaults());
    }
  }

  @Nested
  @DisplayName("Overrides")
  class Overrides {

    @BeforeEach
    void preferEnglishThenFrench() {
      service.setProfilePreferences(
          viewer,
          ProfileLanguagePreferences.builder()
              .audioLanguages(List.of(Language.EN, Language.FR))
              .subtitleLanguage(Optional.of(Language.EN))
              .build());
    }

    @Test
    @DisplayName("Should list Japanese first from the series when the series overrides the audio")
    void shouldListJapaneseFirstFromTheSeriesWhenTheSeriesOverridesTheAudio() {
      setAudio(series, Language.JA);

      var preference = preferenceOf(episode);

      assertThat(preference.audioLanguages())
          .containsExactly(Language.JA, Language.EN, Language.FR);
      assertThat(preference.audioSource()).isEqualTo(PreferenceSource.SERIES);
    }

    @Test
    @DisplayName("Should take the season's audio when the season and the series both set one")
    void shouldTakeTheSeasonsAudioWhenTheSeasonAndTheSeriesBothSetOne() {
      setAudio(series, Language.JA);
      setAudio(season, Language.DE);

      var preference = preferenceOf(episode);

      assertThat(preference.audioLanguages())
          .containsExactly(Language.DE, Language.JA, Language.EN, Language.FR);
      assertThat(preference.audioSource()).isEqualTo(PreferenceSource.SEASON);
    }

    @Test
    @DisplayName("Should take the episode's audio when every level sets one")
    void shouldTakeTheEpisodesAudioWhenEveryLevelSetsOne() {
      setAudio(series, Language.JA);
      setAudio(season, Language.DE);
      setAudio(episode, Language.IT);

      var preference = preferenceOf(episode);

      assertThat(preference.audioLanguages().getFirst()).isEqualTo(Language.IT);
      assertThat(preference.audioSource()).isEqualTo(PreferenceSource.TITLE);
    }

    @Test
    @DisplayName("Should list a language once when two levels name it")
    void shouldListALanguageOnceWhenTwoLevelsNameIt() {
      setAudio(series, Language.FR);
      setAudio(episode, Language.FR);

      assertThat(preferenceOf(episode).audioLanguages()).containsExactly(Language.FR, Language.EN);
    }

    @Test
    @DisplayName("Should inherit the audio from the series when the episode sets only subtitles")
    void shouldInheritTheAudioFromTheSeriesWhenTheEpisodeSetsOnlySubtitles() {
      setAudio(series, Language.JA);
      change(
          episode,
          LanguageOverrideChange.builder()
              .subtitleLanguage(OverrideFieldChange.replaceWith(Optional.of(Language.DE)))
              .build());

      var preference = preferenceOf(episode);

      assertThat(preference.audioLanguages().getFirst()).isEqualTo(Language.JA);
      assertThat(preference.audioSource()).isEqualTo(PreferenceSource.SERIES);
      assertThat(preference.subtitleLanguage()).contains(Language.DE);
      assertThat(preference.subtitleSource()).isEqualTo(PreferenceSource.TITLE);
      assertThat(preference.subtitleMode()).isEqualTo(SubtitlePreferenceMode.FORCED);
    }

    @Test
    @DisplayName("Should take the subtitle mode from the season when the series sets the language")
    void shouldTakeTheSubtitleModeFromTheSeasonWhenTheSeriesSetsTheLanguage() {
      change(
          series,
          LanguageOverrideChange.builder()
              .subtitleLanguage(OverrideFieldChange.replaceWith(Optional.of(Language.FR)))
              .build());
      change(
          season,
          LanguageOverrideChange.builder()
              .subtitleMode(
                  OverrideFieldChange.replaceWith(Optional.of(SubtitlePreferenceMode.ALWAYS)))
              .build());

      var preference = preferenceOf(episode);

      assertThat(preference.subtitleLanguage()).contains(Language.FR);
      assertThat(preference.subtitleMode()).isEqualTo(SubtitlePreferenceMode.ALWAYS);
      assertThat(preference.subtitleSource()).isEqualTo(PreferenceSource.SEASON);
    }

    @Test
    @DisplayName("Should keep the fields a change leaves out when the override changes")
    void shouldKeepTheFieldsAChangeLeavesOutWhenTheOverrideChanges() {
      change(
          series,
          LanguageOverrideChange.builder()
              .audioLanguage(OverrideFieldChange.replaceWith(Optional.of(Language.JA)))
              .subtitleLanguage(OverrideFieldChange.replaceWith(Optional.of(Language.EN)))
              .build());

      setAudio(series, Language.KO);

      var preference = preferenceOf(series);
      assertThat(preference.audioLanguages().getFirst()).isEqualTo(Language.KO);
      assertThat(preference.subtitleLanguage()).contains(Language.EN);
      assertThat(preference.subtitleSource()).isEqualTo(PreferenceSource.SERIES);
    }

    @Test
    @DisplayName("Should inherit the Profile's audio when a change empties the series' audio")
    void shouldInheritTheProfilesAudioWhenAChangeEmptiesTheSeriesAudio() {
      setAudio(series, Language.JA);

      change(
          series,
          LanguageOverrideChange.builder()
              .audioLanguage(OverrideFieldChange.replaceWith(Optional.empty()))
              .build());

      var preference = preferenceOf(episode);
      assertThat(preference.audioLanguages()).containsExactly(Language.EN, Language.FR);
      assertThat(preference.audioSource()).isEqualTo(PreferenceSource.PROFILE);
      assertThat(repository.overrideCount()).isZero();
    }

    @Test
    @DisplayName("Should restore the series' choice when the episode's override is cleared")
    void shouldRestoreTheSeriesChoiceWhenTheEpisodesOverrideIsCleared() {
      setAudio(series, Language.JA);
      setAudio(episode, Language.IT);

      var outcome = service.clearLanguageOverride(viewer, episode.getId());

      assertThat(outcome).isEqualTo(Outcome.<BaseCollectable<?>, Object>accepted(episode));
      var preference = preferenceOf(episode);
      assertThat(preference.audioLanguages().getFirst()).isEqualTo(Language.JA);
      assertThat(preference.audioSource()).isEqualTo(PreferenceSource.SERIES);
    }

    @Test
    @DisplayName("Should return the Profile's preferences when no level overrides them")
    void shouldReturnTheProfilesPreferencesWhenNoLevelOverridesThem() {
      var preference = preferenceOf(episode);

      assertThat(preference)
          .isEqualTo(
              LanguagePreference.builder()
                  .audioLanguages(List.of(Language.EN, Language.FR))
                  .subtitleLanguage(Optional.of(Language.EN))
                  .subtitleMode(SubtitlePreferenceMode.FORCED)
                  .audioSource(PreferenceSource.PROFILE)
                  .subtitleSource(PreferenceSource.PROFILE)
                  .build());
    }

    @Test
    @DisplayName("Should apply a movie's override to that movie as its title's choice")
    void shouldApplyAMoviesOverrideToThatMovieAsItsTitlesChoice() {
      var movie = movies.save(buildMovie("Movie"));
      setAudio(movie, Language.ES);

      var preference = preferenceOf(movie);

      assertThat(preference.audioLanguages().getFirst()).isEqualTo(Language.ES);
      assertThat(preference.audioSource()).isEqualTo(PreferenceSource.TITLE);
    }

    @Test
    @DisplayName("Should keep another Profile's preferences when one Profile overrides a series")
    void shouldKeepAnotherProfilesPreferencesWhenOneProfileOverridesASeries() {
      setAudio(series, Language.JA);

      var other = service.languagePreferences(UUID.randomUUID(), List.of(episode.getId()));

      assertThat(other.get(episode.getId()).audioLanguages()).isEmpty();
      assertThat(other.get(episode.getId()).audioSource()).isEqualTo(PreferenceSource.PROFILE);
    }

    @Test
    @DisplayName("Should return the overridden title when the override is set")
    void shouldReturnTheOverriddenTitleWhenTheOverrideIsSet() {
      var outcome = setAudio(season, Language.JA);

      assertThat(outcome).isEqualTo(Outcome.<BaseCollectable<?>, Object>accepted(season));
    }

    @Test
    @DisplayName("Should reject the change and store nothing when no title has the id")
    void shouldRejectTheChangeAndStoreNothingWhenNoTitleHasTheId() {
      var missing = UUID.randomUUID();

      var set =
          service.setLanguageOverride(
              viewer,
              SetLanguageOverrideCommand.builder()
                  .itemId(missing)
                  .change(audioChange(Language.JA))
                  .build());
      var cleared = service.clearLanguageOverride(viewer, missing);

      assertThat(List.of(set, cleared))
          .containsOnly(Outcome.rejected(new LanguageOverrideRejection.ItemNotFound(missing)));
      assertThat(repository.overrideCount()).isZero();
    }

    @Test
    @DisplayName("Should refuse and store nothing when the viewer may not set an override")
    void shouldRefuseAndStoreNothingWhenTheViewerMayNotSetAnOverride() {
      allowOnlyLanguagePreferencesOf(UUID.randomUUID());
      var command =
          SetLanguageOverrideCommand.builder()
              .itemId(series.getId())
              .change(audioChange(Language.JA))
              .build();
      var seriesId = series.getId();

      assertThatThrownBy(() -> service.setLanguageOverride(viewer, command))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> service.clearLanguageOverride(viewer, seriesId))
          .isInstanceOf(AccessDeniedException.class);
      assertThat(repository.overrideCount()).isZero();
    }

    @Test
    @DisplayName("Should give the Profile's preferences to an id that names no title")
    void shouldGiveTheProfilesPreferencesToAnIdThatNamesNoTitle() {
      var missing = UUID.randomUUID();

      var preferences = service.languagePreferences(viewer.profileId(), List.of(missing));

      assertThat(preferences.get(missing).audioLanguages())
          .containsExactly(Language.EN, Language.FR);
    }
  }

  private void allowOnlyLanguagePreferencesOf(UUID profileId) {
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.SetLanguagePreferences(var target) && target.equals(profileId)
                ? new Decision.Allowed<>(AuthorizationUnit.INSTANCE)
                : new Decision.Denied<>(Decision.DenialReason.POLICY));
  }

  private Outcome<BaseCollectable<?>, LanguageOverrideRejection> setAudio(
      BaseCollectable<?> title, Language language) {
    return change(title, audioChange(language));
  }

  private static LanguageOverrideChange audioChange(Language language) {
    return LanguageOverrideChange.builder()
        .audioLanguage(OverrideFieldChange.replaceWith(Optional.of(language)))
        .build();
  }

  private Outcome<BaseCollectable<?>, LanguageOverrideRejection> change(
      BaseCollectable<?> title, LanguageOverrideChange change) {
    return service.setLanguageOverride(
        viewer, SetLanguageOverrideCommand.builder().itemId(title.getId()).change(change).build());
  }

  private LanguagePreference preferenceOf(BaseCollectable<?> title) {
    return service
        .languagePreferences(viewer.profileId(), List.of(title.getId()))
        .get(title.getId());
  }
}
