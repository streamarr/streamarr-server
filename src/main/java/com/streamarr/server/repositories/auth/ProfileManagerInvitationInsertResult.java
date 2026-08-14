package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import java.util.Objects;

public record ProfileManagerInvitationInsertResult(
    ProfileManagerInvitation invitation, boolean inserted) {

  public ProfileManagerInvitationInsertResult {
    Objects.requireNonNull(invitation);
  }
}
