package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsTypeResolver;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.language.Language;
import com.streamarr.server.domain.language.LanguageOverrideChange;
import com.streamarr.server.domain.language.LanguagePreference;
import com.streamarr.server.domain.language.OverrideFieldChange;
import com.streamarr.server.domain.language.ProfileLanguagePreferences;
import com.streamarr.server.domain.language.SubtitlePreferenceMode;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.graphql.dataloaders.LanguagePreferenceDataLoader;
import com.streamarr.server.graphql.dataloaders.LanguagePreferenceLoaderKey;
import com.streamarr.server.graphql.dto.SelectedProfileState;
import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.language.ClearLanguageOverrideInput;
import com.streamarr.server.graphql.mutation.language.LanguageOverrideError;
import com.streamarr.server.graphql.mutation.language.LanguageOverridePayload;
import com.streamarr.server.graphql.mutation.language.LanguagePreferenceErrors;
import com.streamarr.server.graphql.mutation.language.SetLanguagePreferencesInput;
import com.streamarr.server.graphql.mutation.language.SetLanguagePreferencesPayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.language.LanguageOverrideRejection;
import com.streamarr.server.services.language.LanguagePreferenceService;
import com.streamarr.server.services.language.SetLanguageOverrideCommand;
import com.streamarr.server.services.mutation.Outcome;
import graphql.schema.DataFetchingEnvironment;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;

@DgsComponent
@RequiredArgsConstructor
public class LanguagePreferenceResolver {

  private static final String ITEM_ID = "itemId";
  private static final String INVALID_ITEM_ID = "Enter a valid item ID.";
  private static final String ITEM_NOT_FOUND = "This item no longer exists.";

  private final AuthorizationService authorizationService;
  private final LanguagePreferenceService languagePreferenceService;

  @DgsData(parentType = "Me", field = "languagePreferences")
  public ProfileLanguagePreferences profileLanguagePreferences() {
    return languagePreferenceService
        .profilePreferences(authorizationService.currentIdentity())
        .orElse(null);
  }

  @DgsMutation
  public SetLanguagePreferencesPayload setLanguagePreferences(
      @InputArgument SetLanguagePreferencesInput input) {
    var preferences =
        ProfileLanguagePreferences.builder()
            .audioLanguages(input.audioLanguages())
            .subtitleLanguage(Optional.ofNullable(input.subtitleLanguage()))
            .subtitleMode(input.subtitleMode())
            .build();
    return MutationPayloads.payload(
        languagePreferenceService.setProfilePreferences(
            authorizationService.currentIdentity(), preferences),
        LanguagePreferenceErrors::toPreferredAudioLanguagesError,
        SetLanguagePreferencesPayload::new);
  }

  /**
   * Each language field that the input leaves out keeps its stored value, and a field set to null
   * inherits, so the raw input map is read: a bound input object cannot tell the two apart.
   */
  @DgsMutation
  public LanguageOverridePayload setLanguageOverride(
      @InputArgument("input") Map<String, Object> input) {
    var change =
        LanguageOverrideChange.builder()
            .audioLanguage(fieldChange(input, "audioLanguage", Language::valueOf))
            .subtitleLanguage(fieldChange(input, "subtitleLanguage", Language::valueOf))
            .subtitleMode(fieldChange(input, "subtitleMode", SubtitlePreferenceMode::valueOf))
            .build();
    return withItemId(
        String.valueOf(input.get(ITEM_ID)),
        itemId ->
            languagePreferenceService.setLanguageOverride(
                authorizationService.currentIdentity(),
                SetLanguageOverrideCommand.builder().itemId(itemId).change(change).build()));
  }

  private static <T> OverrideFieldChange<T> fieldChange(
      Map<String, Object> input, String field, Function<String, T> parse) {
    if (!input.containsKey(field)) {
      return OverrideFieldChange.keep();
    }

    return OverrideFieldChange.replaceWith(
        Optional.ofNullable(input.get(field)).map(String::valueOf).map(parse));
  }

  @DgsMutation
  public LanguageOverridePayload clearLanguageOverride(
      @InputArgument ClearLanguageOverrideInput input) {
    return withItemId(
        input.itemId(),
        itemId ->
            languagePreferenceService.clearLanguageOverride(
                authorizationService.currentIdentity(), itemId));
  }

  private static LanguageOverridePayload withItemId(
      String rawItemId,
      Function<UUID, Outcome<BaseCollectable<?>, LanguageOverrideRejection>> change) {
    return MutationPayloads.withUuid(
        rawItemId,
        itemId ->
            MutationPayloads.payload(
                change.apply(itemId),
                LanguagePreferenceResolver::languageOverrideError,
                LanguageOverridePayload::new),
        () ->
            MutationPayloads.inputError(
                new LanguageOverrideError.InvalidIdError(INVALID_ITEM_ID, InputPath.of(ITEM_ID)),
                LanguageOverridePayload::new));
  }

  private static LanguageOverrideError languageOverrideError(LanguageOverrideRejection rejection) {
    return switch (rejection) {
      case LanguageOverrideRejection.ItemNotFound _ ->
          new LanguageOverrideError.ItemNotFoundError(ITEM_NOT_FOUND, InputPath.of(ITEM_ID));
    };
  }

  @DgsTypeResolver(name = "LanguagePreferenceItem")
  public String resolveLanguagePreferenceItem(Object item) {
    return switch (item) {
      case Movie _ -> "Movie";
      case Episode _ -> "Episode";
      case Season _ -> "Season";
      case Series _ -> "Series";
      default ->
          throw new IllegalArgumentException(
              "Unknown language preference item type: " + item.getClass().getSimpleName());
    };
  }

  @DgsData(parentType = "SelectedProfileState", field = "languagePreference")
  public CompletableFuture<LanguagePreference> languagePreference(DataFetchingEnvironment dfe) {
    SelectedProfileState state = dfe.getSource();
    DataLoader<LanguagePreferenceLoaderKey, LanguagePreference> loader =
        dfe.getDataLoader(LanguagePreferenceDataLoader.NAME);
    return loader.load(new LanguagePreferenceLoaderKey(state.profileId(), state.titleId()));
  }
}
