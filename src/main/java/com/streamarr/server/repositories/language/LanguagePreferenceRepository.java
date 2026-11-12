package com.streamarr.server.repositories.language;

import com.streamarr.server.domain.language.LanguageOverride;
import com.streamarr.server.domain.language.LanguageOverrideChange;
import com.streamarr.server.domain.language.OverrideScope;
import com.streamarr.server.domain.language.ProfileLanguagePreferences;
import com.streamarr.server.domain.language.TitleLineage;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A Profile's language preferences and its language overrides of titles (ADR 0043). */
public interface LanguagePreferenceRepository {

  /** The Profile's own preferences; empty when no Profile has the id. */
  Optional<ProfileLanguagePreferences> findProfilePreferences(UUID profileId);

  /**
   * The own preferences of each of the Profiles that exist, keyed by its id, in one statement. An
   * id that names no Profile is left out.
   */
  Map<UUID, ProfileLanguagePreferences> findProfilePreferences(Collection<UUID> profileIds);

  /** Replaces the Profile's own preferences; false when no Profile has the id. */
  boolean trySetProfilePreferences(UUID profileId, ProfileLanguagePreferences preferences);

  /** The lineage of each id that names a movie, a series, a season or an episode. */
  Map<UUID, TitleLineage> findLineages(Collection<UUID> titleIds);

  /** The Profile's overrides of the titles, keyed by title id. */
  Map<UUID, LanguageOverride> findOverrides(UUID profileId, Collection<UUID> titleIds);

  /**
   * Applies the change to the Profile's override of the scope in one statement, so concurrent
   * changes to different fields both land. An override left inheriting every field is removed.
   */
  void changeOverride(UUID profileId, OverrideScope scope, LanguageOverrideChange change);

  void deleteOverride(UUID profileId, OverrideScope scope);
}
