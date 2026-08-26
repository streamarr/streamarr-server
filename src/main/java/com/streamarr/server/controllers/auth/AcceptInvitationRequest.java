package com.streamarr.server.controllers.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

/** Code and new password are secrets; consumed synchronously, never echoed. */
@Builder
public record AcceptInvitationRequest(
    @NotBlank String code,
    @NotBlank String displayName,
    @NotBlank String password,
    Boolean cookieMode) {

  public static class AcceptInvitationRequestBuilder {

    @Override
    public String toString() {
      return "AcceptInvitationRequestBuilder[code=REDACTED, displayName=%s, password=REDACTED,"
              .formatted(displayName)
          + " cookieMode=%s]".formatted(cookieMode);
    }
  }

  @Override
  public String toString() {
    return "AcceptInvitationRequest[code=REDACTED, displayName=%s, password=REDACTED, cookieMode=%s]"
        .formatted(displayName, cookieMode);
  }
}
