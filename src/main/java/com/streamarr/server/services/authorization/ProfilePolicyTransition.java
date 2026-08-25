package com.streamarr.server.services.authorization;

import com.streamarr.server.domain.auth.ProfileKind;

/**
 * The authorized, normalized policy transition a profile mutation must write (ADR 0025): the target
 * kind and ceiling exactly as evaluated, so the write can be conditional on the state the decision
 * was made against.
 */
public record ProfilePolicyTransition(
    ProfileKind targetKind, Integer targetCeiling, Classification classification) {

  /** How the transition classifies under ADR 0024's supervision rules. */
  public enum Classification {
    /** Same kind, still (or newly, for an unlinked Adult) restricted — supervisors may edit. */
    ORDINARY_EDIT,
    /** A kind change that neither lifts the final restriction nor restricts a sovereign Adult. */
    KIND_CHANGE,
    /** Leaves the Profile unrestricted Adult; fresh-reauthentication work for anyone. */
    LIFT_FINAL_RESTRICTION,
    /** Restricts a linked unrestricted Adult Personal Profile; a ServerAdmin override. */
    RESTRICT_SOVEREIGN_ADULT
  }
}
