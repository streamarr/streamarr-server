package com.streamarr.server.services.authorization.cedar;

import com.streamarr.server.services.auth.AuthenticatedIdentity;

/** Loads one fact family into the slice; the only place that fact may be set. */
interface FactContributor {

  FactRequirement provides();

  void contribute(AuthenticatedIdentity identity, AuthorizationCheck check, EntitySlice slice);
}
