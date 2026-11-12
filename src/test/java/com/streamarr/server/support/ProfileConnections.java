package com.streamarr.server.support;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccountInvitationService;
import com.streamarr.server.services.auth.AccountInvitationService.AcceptInvitationCommand;
import com.streamarr.server.services.identity.CredentialIssuanceService;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationForProfileCommand;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * Connects a Profile to a new Account through an Account invitation, as a ServerAdmin issues it and
 * its recipient accepts it, and deletes what the connection left behind.
 */
@Builder
public final class ProfileConnections {

  @NonNull private final CredentialIssuanceService credentialIssuance;
  @NonNull private final AccountInvitationService accountInvitations;
  @NonNull private final AccountInvitationRepository invitations;
  @NonNull private final UserAccountRepository accounts;
  @NonNull private final AuthTestSupport auth;
  @NonNull private final String recipientEmail;

  /**
   * The ServerAdmin issues an Account invitation for the Profile, which offers it again to the
   * Household, and the invitation is accepted: the Profile becomes the new Account's Personal
   * Profile. Answers the new Account's id.
   */
  public UUID connect(Profile profile, TestIdentity serverAdmin, TestIdentity reofferedTo) {
    var issued =
        credentialIssuance
            .issueAccountInvitationForProfile(
                auth.identityOf(serverAdmin),
                IssueInvitationForProfileCommand.builder()
                    .recipientEmail(recipientEmail)
                    .householdRole(HouseholdRole.MEMBER)
                    .profileId(profile.getId())
                    .reofferHouseholdIds(List.of(reofferedTo.household().getId()))
                    .build())
            .fold(
                invitation -> invitation,
                rejection -> {
                  throw new IllegalStateException("The invitation was refused: " + rejection);
                });

    return accountInvitations
        .accept(
            AcceptInvitationCommand.builder()
                .code(issued.code())
                .displayName("Ollie")
                .password("a strong passphrase")
                .deviceName("web")
                .ipAddress(ShareSteps.IP_ADDRESS)
                .build())
        .account()
        .getId();
  }

  /** Deletes the invitations sent to the recipient, and the Account that accepted one. */
  public void deleteConnected() {
    invitations.findAll().stream()
        .filter(invitation -> recipientEmail.equalsIgnoreCase(invitation.getRecipientEmail()))
        .forEach(invitations::delete);
    accounts
        .findByEmailIgnoreCase(recipientEmail)
        .ifPresent(account -> auth.deleteAccount(account.getId()));
  }
}
