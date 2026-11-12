package com.streamarr.server.support;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.services.identity.ProfileManagerAdministrationService;
import com.streamarr.server.services.identity.ProfileManagerAdministrationService.IssuedManagerInvitation;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import java.time.Instant;
import lombok.Builder;
import lombok.NonNull;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The manager invitation writes that tests arrange: invitations go through the Profile management
 * service, as a manager sends them.
 */
@Builder
public final class ManagerInvitationSteps {

  @NonNull private final ProfileManagerAdministrationService management;
  @NonNull private final AuthTestSupport auth;
  @NonNull private final ProfileManagerInvitationRepository invitations;
  @NonNull private final TransactionTemplate transactions;

  /** The manager invites whoever has the address to manage the Profile. */
  public IssuedManagerInvitation invite(TestIdentity inviter, Profile profile, String address) {
    return management
        .inviteProfileManager(auth.identityOf(inviter), profile.getId(), address)
        .fold(
            issued -> issued,
            rejection -> {
              throw new IllegalStateException("The invitation was refused: " + rejection);
            });
  }

  /** Moves the invitation's expiry into the past, as if its time ran out. */
  public void lapse(ProfileManagerInvitation invitation) {
    transactions.executeWithoutResult(
        _ -> {
          var lapsed = invitations.findById(invitation.getId()).orElseThrow();
          lapsed.setExpiresAt(Instant.now().minusSeconds(1));
          invitations.saveAndFlush(lapsed);
        });
  }
}
