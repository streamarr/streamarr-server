package com.streamarr.server.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Profile Policy Snapshot Tests")
class ProfilePolicySnapshotTest {

  @Test
  @DisplayName("Should be restricted when an Adult Profile has a Content Ceiling")
  void shouldBeRestrictedWhenAdultProfileHasContentCeiling() {
    var snapshot = new ProfilePolicySnapshot(ProfileKind.ADULT, 12, null);

    assertThat(snapshot.restricted()).isTrue();
  }
}
