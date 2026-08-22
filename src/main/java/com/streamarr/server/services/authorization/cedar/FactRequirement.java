package com.streamarr.server.services.authorization.cedar;

/** A fact family an action needs in its entity slice; each has exactly one contributor. */
enum FactRequirement {
  /** The principal's live {@code enabled} and {@code serverAdmin} attributes from PostgreSQL. */
  LIVE_PRINCIPAL_AUTHORITY
}
