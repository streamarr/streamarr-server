package com.streamarr.server.services.auth;

import java.time.Duration;

/**
 * The invitation lifetime (ADR 0024 §Invitations): how long an Account invitation, a ProfileManager
 * invitation, or an offer of a Profile waits for its answer after it is made.
 */
@FunctionalInterface
public interface InvitationLifetime {

  Duration invitationLifetime();
}
