package com.streamarr.server.services.authorization.cedar;

/** A fact family an action needs in its entity slice; each has exactly one contributor. */
enum FactRequirement {
  /** The principal's live {@code enabled} and {@code serverAdmin} attributes from PostgreSQL. */
  LIVE_PRINCIPAL_AUTHORITY,
  /**
   * The principal's signed facts: role, membership Household, context Household, selected Profile.
   */
  SIGNED_PRINCIPAL_CONTEXT,
  /** Whether the principal may still use its context Household, read live. */
  CONTEXT_HOUSEHOLD_ACCESS,
  /** Whether the principal's session is still live, read live. */
  SESSION_LIVENESS,
  /** The resource Profile's availability, lock, and PIN facts in the context Household. */
  PROFILE_AVAILABILITY,
  /** The resource Profile's management and hosting facts relative to the principal. */
  PROFILE_MANAGEMENT,
  /** The resource Account's membership Household. */
  ACCOUNT_HOUSEHOLD,
  /** The principal's live Household role and membership Household from PostgreSQL. */
  LIVE_PRINCIPAL_HOUSEHOLD,
  /** Whether the principal's own Personal Profile is a live unrestricted Adult. */
  PRINCIPAL_ELIGIBILITY,
  /** Whether the principal supervises the restricted resource Profile in its live Household. */
  PROFILE_SUPERVISION,
  /** The resource Profile's deletability facts: linkage, shares, and the sole-manager rule. */
  PROFILE_DELETION,
  /** The resource Share's lifecycle and the principal's relationships to it, read live. */
  SHARE
}
