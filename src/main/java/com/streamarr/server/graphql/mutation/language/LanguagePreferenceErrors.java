package com.streamarr.server.graphql.mutation.language;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.identity.ProfileRejections;
import com.streamarr.server.services.language.SetLanguagePreferencesRejection;
import lombok.NonNull;

/** The exhaustive mappings from a language preference refusal to its schema error type. */
public final class LanguagePreferenceErrors {

  private static final String AUDIO_LANGUAGES = "audioLanguages";

  private LanguagePreferenceErrors() {}

  public static SetProfileLanguagePreferencesError toSetProfileLanguagePreferencesError(
      @NonNull ProfileRejections.SetProfileLanguagePreferences rejection) {
    return switch (rejection) {
      case ProfileRejections.ProfileNotFound _ ->
          new ProfileNotFoundError("No such Profile.", InputPath.of("profileId"));
      case ProfileRejections.ManagersOnly managersOnly -> ProfileManagersOnlyError.of(managersOnly);
      case ProfileRejections.PreferredAudioLanguagesRefused(var reason) ->
          toPreferredAudioLanguagesError(reason);
    };
  }

  public static PreferredAudioLanguagesError toPreferredAudioLanguagesError(
      @NonNull SetLanguagePreferencesRejection rejection) {
    return switch (rejection) {
      case SetLanguagePreferencesRejection.TooManyAudioLanguages(var maximum) ->
          new PreferredAudioLanguagesError.TooManyAudioLanguagesError(
              "Choose at most %d audio languages.".formatted(maximum),
              InputPath.of(AUDIO_LANGUAGES),
              maximum);
      case SetLanguagePreferencesRejection.RepeatedAudioLanguage(var language, var position) ->
          new PreferredAudioLanguagesError.RepeatedAudioLanguageError(
              "Choose each audio language once.",
              InputPath.element(AUDIO_LANGUAGES, position),
              language);
    };
  }
}
