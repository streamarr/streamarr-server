package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import java.util.UUID;

public record PortableProfileManagerInvitationSummary(
    UUID id,
    UUID profileId,
    UUID invitingAccountId,
    UUID invitedAccountId,
    ProfileManagerInvitationStatus status) {

  public static PortableProfileManagerInvitationSummary from(ProfileManagerInvitation invitation) {
    return new PortableProfileManagerInvitationSummary(
        invitation.getId(),
        invitation.getProfileId(),
        invitation.getInvitingAccountId(),
        invitation.getInvitedAccountId(),
        invitation.getStatus());
  }
}
