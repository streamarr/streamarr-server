package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.EntityUID;
import java.util.UUID;

/** Entity identifiers are opaque ids, never names or emails. */
final class CedarIds {

  static final String ACCOUNT_TYPE = "Streamarr::Account";
  static final String SERVER_TYPE = "Streamarr::Server";
  static final String SERVER_ID = "streamarr";

  private CedarIds() {}

  static EntityUID account(UUID accountId) {
    return uid(ACCOUNT_TYPE, accountId.toString());
  }

  static EntityUID server() {
    return uid(SERVER_TYPE, SERVER_ID);
  }

  static EntityUID uid(String type, String id) {
    return EntityUID.parse(type + "::\"" + id + "\"").orElseThrow();
  }
}
