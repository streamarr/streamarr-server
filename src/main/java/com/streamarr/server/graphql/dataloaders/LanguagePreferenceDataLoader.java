package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.domain.language.LanguagePreference;
import com.streamarr.server.services.language.LanguagePreferenceService;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

@DgsDataLoader(name = LanguagePreferenceDataLoader.NAME)
@RequiredArgsConstructor
public class LanguagePreferenceDataLoader
    implements MappedBatchLoader<LanguagePreferenceLoaderKey, LanguagePreference> {

  public static final String NAME = "languagePreference";

  private final LanguagePreferenceService languagePreferenceService;

  @Override
  public CompletionStage<Map<LanguagePreferenceLoaderKey, LanguagePreference>> load(
      Set<LanguagePreferenceLoaderKey> keys) {
    var result = new HashMap<LanguagePreferenceLoaderKey, LanguagePreference>();
    keys.stream()
        .collect(Collectors.groupingBy(LanguagePreferenceLoaderKey::profileId))
        .forEach(
            (profileId, profileKeys) -> {
              var titleIds =
                  profileKeys.stream().map(LanguagePreferenceLoaderKey::titleId).toList();
              var preferences = languagePreferenceService.languagePreferences(profileId, titleIds);
              profileKeys.forEach(key -> result.put(key, preferences.get(key.titleId())));
            });
    return CompletableFuture.completedFuture(result);
  }
}
