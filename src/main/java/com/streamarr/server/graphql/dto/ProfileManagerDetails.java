package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ProfileManagerSource;
import com.streamarr.server.services.identity.ManagingAccountDetails;

public record ProfileManagerDetails(
    String accountId, String displayName, ProfileManagerSource source, boolean reader) {

  public static ProfileManagerDetails from(ManagingAccountDetails details) {
    return new ProfileManagerDetails(
        details.account().getId().toString(),
        details.account().getDisplayName(),
        details.source(),
        details.reader());
  }
}
