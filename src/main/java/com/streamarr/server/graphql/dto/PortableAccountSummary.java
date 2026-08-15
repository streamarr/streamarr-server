package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.UserAccount;
import java.util.UUID;

public record PortableAccountSummary(UUID id, String displayName) {

  public static PortableAccountSummary from(UserAccount account) {
    return new PortableAccountSummary(account.getId(), account.getDisplayName());
  }
}
