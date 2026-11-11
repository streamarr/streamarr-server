package com.streamarr.server.services.architecturefixture;

import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/** A service that cancels a manager invitation through the repository, not the lifecycle. */
@RequiredArgsConstructor
public class DirectManagerInvitationWriteFixture {

  private final ProfileManagerInvitationRepository invitationRepository;

  @SuppressWarnings("java:S1144") // ArchUnit inspects this uninvoked bytecode fixture.
  private boolean cancelInvitation(UUID invitationId) {
    return invitationRepository.tryCancelPending(invitationId, Instant.now()).isPresent();
  }
}
