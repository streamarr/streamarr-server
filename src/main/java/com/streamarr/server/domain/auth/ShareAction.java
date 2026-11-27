package com.streamarr.server.domain.auth;

import java.util.UUID;
import lombok.NonNull;

/**
 * What one write did to a share that its two sides may be told about, and which Account did it (ADR
 * 0063 §The sharing kinds). The share itself says how the write landed: an answer reads as accepted
 * or rejected from its status, and a rejection that came too late reads as expired.
 */
public sealed interface ShareAction {

  /** A new offer waits. The share records the Account that made it. */
  record Offered() implements ShareAction {}

  /** The Account accepted or rejected the offer. */
  record Answered(@NonNull UUID accountId) implements ShareAction {}

  /** How an active share ended. */
  sealed interface Ending extends ShareAction {}

  /**
   * The Account ended the share from the side it stands on: the Household's side when it is a
   * HouseholdAdmin of the share's Household, otherwise the offering side.
   */
  record EndedBySide(@NonNull UUID accountId) implements Ending {}

  /**
   * A ServerAdmin ended the share administratively, or an operation the Account started ended it on
   * its way, such as a transfer or connecting a Profile to an Account.
   */
  record EndedByServer(@NonNull UUID accountId) implements Ending {}

  /** The share ended because its Household is being deleted, and the share is deleted with it. */
  record EndedWithHousehold() implements Ending {}

  /**
   * A pending offer stopped waiting without an answer: it was canceled, replaced, invalidated or
   * expired, or it became the Profile's structural share when the Profile was linked into that
   * Household. Its requests resolve, and nobody is told anything new.
   */
  record OfferStoppedWaiting() implements ShareAction {}

  /**
   * Any other write: a share made or unmade structural, moved, or about to be deleted with its
   * Profile or its Household.
   */
  record Other() implements ShareAction {}
}
