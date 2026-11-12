package com.streamarr.server.domain.auth;

import java.util.UUID;
import lombok.NonNull;

/**
 * What one write did to a manager invitation, and which Account did it (ADR 0063 §The Profile
 * management kinds). The invitation's status says how the write landed: an answer reads as accepted
 * or declined, and an invitation that stopped waiting reads as invalidated or expired.
 */
public sealed interface ManagerInvitationAction {

  /** A new invitation waits. The invitation records the manager who sent it. */
  record Issued() implements ManagerInvitationAction {}

  /** The invitation's named Account accepted or declined it. */
  record Answered(@NonNull UUID accountId) implements ManagerInvitationAction {}

  /** The manager who sent the invitation, or a ServerAdmin, canceled it. */
  record Canceled(@NonNull UUID accountId) implements ManagerInvitationAction {}

  /**
   * The server withdrew the invitation, because a newer invitation replaced it or because it could
   * no longer be answered, or stored that its time ran out. Nobody is told anything new.
   */
  record StoppedWaiting() implements ManagerInvitationAction {}

  /**
   * The server invalidated the invitation because of what became of its invited Account. It still
   * waits to the Profile's ProfileManagers, who are never told (ADR 0024 §Invitations); only the
   * invited Account's request stops waiting.
   */
  record InvalidatedForItsAccount() implements ManagerInvitationAction {}
}
