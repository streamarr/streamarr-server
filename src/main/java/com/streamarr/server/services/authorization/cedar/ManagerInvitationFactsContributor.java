package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.PrimBool;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The resource manager invitation's parties relative to the principal: the named recipient and the
 * inviter. A missing invitation contributes nothing, and absent facts read as denied.
 */
@Component
@RequiredArgsConstructor
class ManagerInvitationFactsContributor implements FactContributor {

  static final String RECIPIENT_IS_PRINCIPAL = "recipientIsPrincipal";
  static final String INVITED_BY_PRINCIPAL = "invitedByPrincipal";

  private final ProfileManagerInvitationRepository invitationRepository;

  @Override
  public FactRequirement provides() {
    return FactRequirement.MANAGER_INVITATION_FACTS;
  }

  @Override
  public void contribute(
      AuthenticatedIdentity identity, AuthorizationCheck check, EntitySlice slice) {
    var invitation = invitationRepository.findById(check.resourceId());
    if (invitation.isEmpty()) {
      return;
    }

    slice.resourceAttribute(
        RECIPIENT_IS_PRINCIPAL,
        new PrimBool(identity.accountId().equals(invitation.get().getRecipientAccountId())));
    slice.resourceAttribute(
        INVITED_BY_PRINCIPAL,
        new PrimBool(identity.accountId().equals(invitation.get().getInviterAccountId())));
  }
}
