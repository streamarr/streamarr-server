package com.streamarr.server.graphql.dto;

/** The code appears here once and never again. */
public record IssuedManagerInvitation(ManagerInvitationView invitation, String code) {

  @Override
  public String toString() {
    return "IssuedManagerInvitation[invitation=%s, code=REDACTED]".formatted(invitation.id());
  }
}
