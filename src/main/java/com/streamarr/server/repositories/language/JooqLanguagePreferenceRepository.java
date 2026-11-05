package com.streamarr.server.repositories.language;

import static com.streamarr.server.jooq.generated.tables.Episode.EPISODE;
import static com.streamarr.server.jooq.generated.tables.LanguageOverride.LANGUAGE_OVERRIDE;
import static com.streamarr.server.jooq.generated.tables.Movie.MOVIE;
import static com.streamarr.server.jooq.generated.tables.Profile.PROFILE;
import static com.streamarr.server.jooq.generated.tables.Season.SEASON;
import static com.streamarr.server.jooq.generated.tables.Series.SERIES;

import com.streamarr.server.domain.language.Language;
import com.streamarr.server.domain.language.LanguageOverride;
import com.streamarr.server.domain.language.LanguageOverrideChange;
import com.streamarr.server.domain.language.OverrideFieldChange;
import com.streamarr.server.domain.language.OverrideScope;
import com.streamarr.server.domain.language.ProfileLanguagePreferences;
import com.streamarr.server.domain.language.SubtitlePreferenceMode;
import com.streamarr.server.domain.language.TitleKind;
import com.streamarr.server.domain.language.TitleLineage;
import com.streamarr.server.jooq.generated.tables.records.LanguageOverrideRecord;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class JooqLanguagePreferenceRepository implements LanguagePreferenceRepository {

  private final DSLContext dsl;

  @Override
  @Transactional(readOnly = true)
  public Optional<ProfileLanguagePreferences> findProfilePreferences(UUID profileId) {
    return dsl.select(PROFILE.AUDIO_LANGUAGES, PROFILE.SUBTITLE_LANGUAGE, PROFILE.SUBTITLE_MODE)
        .from(PROFILE)
        .where(PROFILE.ID.eq(profileId))
        .fetchOptional(
            row ->
                ProfileLanguagePreferences.builder()
                    .audioLanguages(
                        Arrays.stream(row.get(PROFILE.AUDIO_LANGUAGES))
                            .map(Language::valueOf)
                            .toList())
                    .subtitleLanguage(
                        Optional.ofNullable(row.get(PROFILE.SUBTITLE_LANGUAGE))
                            .map(Language::valueOf))
                    .subtitleMode(SubtitlePreferenceMode.valueOf(row.get(PROFILE.SUBTITLE_MODE)))
                    .build());
  }

  @Override
  @Transactional
  public boolean trySetProfilePreferences(UUID profileId, ProfileLanguagePreferences preferences) {
    return dsl.update(PROFILE)
            .set(
                PROFILE.AUDIO_LANGUAGES,
                preferences.audioLanguages().stream().map(Language::name).toArray(String[]::new))
            .set(
                PROFILE.SUBTITLE_LANGUAGE,
                preferences.subtitleLanguage().map(Language::name).orElse(null))
            .set(PROFILE.SUBTITLE_MODE, preferences.subtitleMode().name())
            .where(PROFILE.ID.eq(profileId))
            .execute()
        == 1;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, TitleLineage> findLineages(Collection<UUID> titleIds) {
    var lineages = new HashMap<UUID, TitleLineage>();
    dsl.select(MOVIE.ID)
        .from(MOVIE)
        .where(MOVIE.ID.in(titleIds))
        .forEach(row -> lineages.put(row.value1(), lineage(scope(TitleKind.MOVIE, row.value1()))));
    dsl.select(SERIES.ID)
        .from(SERIES)
        .where(SERIES.ID.in(titleIds))
        .forEach(row -> lineages.put(row.value1(), lineage(scope(TitleKind.SERIES, row.value1()))));
    dsl.select(SEASON.ID, SEASON.SERIES_ID)
        .from(SEASON)
        .where(SEASON.ID.in(titleIds))
        .forEach(
            row ->
                lineages.put(
                    row.value1(),
                    lineage(
                        scope(TitleKind.SEASON, row.value1()),
                        scope(TitleKind.SERIES, row.value2()))));
    dsl.select(EPISODE.ID, EPISODE.SEASON_ID, SEASON.SERIES_ID)
        .from(EPISODE)
        .join(SEASON)
        .on(SEASON.ID.eq(EPISODE.SEASON_ID))
        .where(EPISODE.ID.in(titleIds))
        .forEach(
            row ->
                lineages.put(
                    row.value1(),
                    lineage(
                        scope(TitleKind.EPISODE, row.value1()),
                        scope(TitleKind.SEASON, row.value2()),
                        scope(TitleKind.SERIES, row.value3()))));
    return lineages;
  }

  private static TitleLineage lineage(OverrideScope... scopes) {
    return new TitleLineage(List.of(scopes));
  }

  private static OverrideScope scope(TitleKind kind, UUID titleId) {
    return new OverrideScope(kind, titleId);
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, LanguageOverride> findOverrides(UUID profileId, Collection<UUID> titleIds) {
    var titleId =
        DSL.coalesce(
            LANGUAGE_OVERRIDE.MOVIE_ID,
            LANGUAGE_OVERRIDE.SERIES_ID,
            LANGUAGE_OVERRIDE.SEASON_ID,
            LANGUAGE_OVERRIDE.EPISODE_ID);
    var overrides = new HashMap<UUID, LanguageOverride>();
    dsl.select(
            titleId,
            LANGUAGE_OVERRIDE.AUDIO_LANGUAGE,
            LANGUAGE_OVERRIDE.SUBTITLE_LANGUAGE,
            LANGUAGE_OVERRIDE.SUBTITLE_MODE)
        .from(LANGUAGE_OVERRIDE)
        .where(LANGUAGE_OVERRIDE.PROFILE_ID.eq(profileId))
        .and(
            DSL.or(
                LANGUAGE_OVERRIDE.MOVIE_ID.in(titleIds),
                LANGUAGE_OVERRIDE.SERIES_ID.in(titleIds),
                LANGUAGE_OVERRIDE.SEASON_ID.in(titleIds),
                LANGUAGE_OVERRIDE.EPISODE_ID.in(titleIds)))
        .forEach(row -> overrides.put(row.value1(), toOverride(row)));
    return overrides;
  }

  private static LanguageOverride toOverride(Record row) {
    return LanguageOverride.builder()
        .audioLanguage(
            Optional.ofNullable(row.get(LANGUAGE_OVERRIDE.AUDIO_LANGUAGE)).map(Language::valueOf))
        .subtitleLanguage(
            Optional.ofNullable(row.get(LANGUAGE_OVERRIDE.SUBTITLE_LANGUAGE))
                .map(Language::valueOf))
        .subtitleMode(
            Optional.ofNullable(row.get(LANGUAGE_OVERRIDE.SUBTITLE_MODE))
                .map(SubtitlePreferenceMode::valueOf))
        .build();
  }

  // One upsert applies the change, so two changes to different fields of one override both land.
  // A field the change keeps is left out of the update. The delete that follows removes the row
  // only while every field is empty, so it cannot remove a field that another change stored.
  @Override
  @Transactional
  public void changeOverride(UUID profileId, OverrideScope scope, LanguageOverrideChange change) {
    var titleColumn = titleColumnOf(scope.kind());
    var inserted = new LanguageOverrideRecord();
    inserted.set(LANGUAGE_OVERRIDE.PROFILE_ID, profileId);
    inserted.set(titleColumn, scope.titleId());
    inserted.setAudioLanguage(replaced(change.audioLanguage(), Language::name));
    inserted.setSubtitleLanguage(replaced(change.subtitleLanguage(), Language::name));
    inserted.setSubtitleMode(replaced(change.subtitleMode(), SubtitlePreferenceMode::name));

    var updates = new HashMap<TableField<LanguageOverrideRecord, String>, String>();
    putReplaced(updates, LANGUAGE_OVERRIDE.AUDIO_LANGUAGE, change.audioLanguage(), Language::name);
    putReplaced(
        updates, LANGUAGE_OVERRIDE.SUBTITLE_LANGUAGE, change.subtitleLanguage(), Language::name);
    putReplaced(
        updates,
        LANGUAGE_OVERRIDE.SUBTITLE_MODE,
        change.subtitleMode(),
        SubtitlePreferenceMode::name);

    var upsert =
        dsl.insertInto(LANGUAGE_OVERRIDE)
            .set(inserted)
            .onConflict(titleColumn, LANGUAGE_OVERRIDE.PROFILE_ID)
            .where(titleColumn.isNotNull());
    if (updates.isEmpty()) {
      upsert.doNothing().execute();
    } else {
      upsert.doUpdate().set(updates).execute();
    }

    dsl.deleteFrom(LANGUAGE_OVERRIDE)
        .where(LANGUAGE_OVERRIDE.PROFILE_ID.eq(profileId))
        .and(titleColumn.eq(scope.titleId()))
        .and(LANGUAGE_OVERRIDE.AUDIO_LANGUAGE.isNull())
        .and(LANGUAGE_OVERRIDE.SUBTITLE_LANGUAGE.isNull())
        .and(LANGUAGE_OVERRIDE.SUBTITLE_MODE.isNull())
        .execute();
  }

  private static <T> String replaced(OverrideFieldChange<T> change, Function<T, String> name) {
    return switch (change) {
      case OverrideFieldChange.Keep<T> _ -> null;
      case OverrideFieldChange.Replace<T>(var value) -> value.map(name).orElse(null);
    };
  }

  private static <T> void putReplaced(
      Map<TableField<LanguageOverrideRecord, String>, String> updates,
      TableField<LanguageOverrideRecord, String> field,
      OverrideFieldChange<T> change,
      Function<T, String> name) {
    if (change instanceof OverrideFieldChange.Replace<T>) {
      updates.put(field, replaced(change, name));
    }
  }

  @Override
  @Transactional
  public void deleteOverride(UUID profileId, OverrideScope scope) {
    dsl.deleteFrom(LANGUAGE_OVERRIDE)
        .where(LANGUAGE_OVERRIDE.PROFILE_ID.eq(profileId))
        .and(titleColumnOf(scope.kind()).eq(scope.titleId()))
        .execute();
  }

  private static TableField<LanguageOverrideRecord, UUID> titleColumnOf(TitleKind kind) {
    return switch (kind) {
      case MOVIE -> LANGUAGE_OVERRIDE.MOVIE_ID;
      case SERIES -> LANGUAGE_OVERRIDE.SERIES_ID;
      case SEASON -> LANGUAGE_OVERRIDE.SEASON_ID;
      case EPISODE -> LANGUAGE_OVERRIDE.EPISODE_ID;
    };
  }
}
