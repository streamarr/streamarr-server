package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.services.auth.PortableIdentityQueryService.ProfileManagerInvitationView;
import java.util.UUID;

public record PortableProfileManagerInvitationSummary(
    UUID id,
    UUID profileId,
    UUID invitingAccountId,
    UUID invitedAccountId,
    ProfileManagerInvitationStatus status,
    PortableProfileSummary profile,
    PortableAccountSummary invitingAccount,
    PortableAccountSummary invitedAccount) {

  public static PortableProfileManagerInvitationSummary from(ProfileManagerInvitation invitation) {
    return new PortableProfileManagerInvitationSummary(
        invitation.getId(),
        invitation.getProfileId(),
        invitation.getInvitingAccountId(),
        invitation.getInvitedAccountId(),
        invitation.getStatus(),
        null,
        null,
        null);
  }

  public static PortableProfileManagerInvitationSummary from(ProfileManagerInvitationView view) {
    var invitation = view.invitation();
    return new PortableProfileManagerInvitationSummary(
        invitation.getId(),
        invitation.getProfileId(),
        invitation.getInvitingAccountId(),
        invitation.getInvitedAccountId(),
        invitation.getStatus(),
        PortableProfileSummary.from(view.profile()),
        PortableAccountSummary.from(view.invitingAccount()),
        PortableAccountSummary.from(view.invitedAccount()));
  }
}
