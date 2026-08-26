package com.streamarr.server.fixtures;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileKind;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class AccountInvitationFixture {

  private AccountInvitationFixture() {}

  /**
   * A pending, unexpired Member invitation for an Adult Profile; target and issuer are the
   * caller's.
   */
  public static AccountInvitation.AccountInvitationBuilder<?, ?> pendingInvitationBuilder() {
    return AccountInvitation.builder()
        .recipientEmail("invited-" + UUID.randomUUID() + "@example.com")
        .householdRole(HouseholdRole.MEMBER)
        .profileName("Invited")
        .profileKind(ProfileKind.ADULT)
        .expiresAt(Instant.now().plus(Duration.ofDays(1)))
        .publicId(UUID.randomUUID().toString())
        .secretDigest(new byte[32]);
  }
}
