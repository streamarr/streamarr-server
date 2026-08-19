package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.EntityUID;
import java.util.Set;

/** The Cedar actions Streamarr evaluates; names match {@code streamarr.cedarschema} exactly. */
enum Action {
  ADD_LIBRARY("addLibrary"),
  REMOVE_LIBRARY("removeLibrary"),
  SCAN_LIBRARY("scanLibrary"),
  REFRESH_LIBRARY("refreshLibrary");

  private static final String ACTION_TYPE = "Streamarr::Action";

  private final String cedarName;

  Action(String cedarName) {
    this.cedarName = cedarName;
  }

  String cedarName() {
    return cedarName;
  }

  EntityUID uid() {
    return CedarIds.uid(ACTION_TYPE, cedarName);
  }

  /** Facts the slice must carry for this action; every member of serverAdministration is live. */
  Set<FactRequirement> facts() {
    return Set.of(FactRequirement.LIVE_PRINCIPAL_AUTHORITY);
  }
}
