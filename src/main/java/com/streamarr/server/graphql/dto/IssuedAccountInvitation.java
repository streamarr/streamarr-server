package com.streamarr.server.graphql.dto;

/** The code appears here once and never again. */
public record IssuedAccountInvitation(AccountInvitationView invitation, String code) {

  @Override
  public String toString() {
    return "IssuedAccountInvitation[invitation=%s, code=REDACTED]".formatted(invitation.id());
  }
}
