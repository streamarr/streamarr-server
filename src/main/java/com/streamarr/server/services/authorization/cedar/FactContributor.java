package com.streamarr.server.services.authorization.cedar;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.Map;

/** Loads one fact family into the slice; the only place that fact may be set. */
interface FactContributor {

  FactRequirement provides();

  void contribute(AuthenticatedIdentity identity, AuthorizationCheck check, EntitySlice slice);

  /**
   * Loads the fact into the slice of each check of a batch, which holds reads only. A contributor
   * that can read its fact for many resources at once overrides this.
   */
  default void contributeEach(
      AuthenticatedIdentity identity, Map<AuthorizationCheck, EntitySlice> slices) {
    slices.forEach((check, slice) -> contribute(identity, check, slice));
  }
}
