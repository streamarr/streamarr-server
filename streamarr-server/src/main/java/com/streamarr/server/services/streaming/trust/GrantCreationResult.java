package com.streamarr.server.services.streaming.trust;

import java.util.Objects;

public sealed interface GrantCreationResult {

  record Created(EnrollmentGrant grant, PublicTrustBundle publicTrustBundle)
      implements GrantCreationResult {

    public Created {
      requireExactBundle(grant, publicTrustBundle);
    }
  }

  record Retained(EnrollmentGrant grant, PublicTrustBundle publicTrustBundle)
      implements GrantCreationResult {

    public Retained {
      requireExactBundle(grant, publicTrustBundle);
    }
  }

  record Conflict(GrantCreationConflict reason) implements GrantCreationResult {

    public Conflict {
      Objects.requireNonNull(reason);
    }
  }

  private static void requireExactBundle(
      EnrollmentGrant grant, PublicTrustBundle publicTrustBundle) {
    Objects.requireNonNull(grant);
    Objects.requireNonNull(publicTrustBundle);
    if (!grant.trustBundle().installationId().equals(publicTrustBundle.installationId())
        || grant.trustBundle().version() != publicTrustBundle.version()) {
      throw new IllegalArgumentException("Enrollment grant must carry its exact public bundle");
    }
  }
}
