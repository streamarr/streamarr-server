package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.graphql.dto.SelectableProfile;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.identity.IdentityQueryService;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

/**
 * Reads the selected Profile once for a response: every media title in it carries the same
 * identity, so the identity is the key.
 */
@DgsDataLoader(name = SelectedProfileDataLoader.NAME)
@RequiredArgsConstructor
public class SelectedProfileDataLoader
    implements MappedBatchLoader<AuthenticatedIdentity, SelectableProfile> {

  public static final String NAME = "selectedProfile";

  private final IdentityQueryService identityQueryService;

  @Override
  public CompletionStage<Map<AuthenticatedIdentity, SelectableProfile>> load(
      Set<AuthenticatedIdentity> identities) {
    var profiles = new HashMap<AuthenticatedIdentity, SelectableProfile>();
    for (var identity : identities) {
      identityQueryService
          .selectedProfile(identity)
          .map(SelectableProfile::from)
          .ifPresent(profile -> profiles.put(identity, profile));
    }

    return CompletableFuture.completedFuture(profiles);
  }
}
