package com.streamarr.server.services.architecturefixture;

import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/** A service that ends a share through the repository instead of the share lifecycle. */
@RequiredArgsConstructor
public class DirectShareWriteFixture {

  private final ProfileHouseholdShareRepository shareRepository;

  @SuppressWarnings("java:S1144") // ArchUnit inspects this uninvoked bytecode fixture.
  private boolean endShare(UUID shareId) {
    return shareRepository.tryEndActive(shareId, Instant.now()).isPresent();
  }
}
