package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfilePolicyTarget;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ProfileCastSupport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What the lock preview shows a caller who may not see every Household where the Profile is
 * available (ADR 0024 §PIN safety). Whether the caller manages a Profile is read from PostgreSQL.
 */
@Tag("IntegrationTest")
@DisplayName("Profile Lock Preview Visibility Integration Tests")
class ProfileLockPreviewVisibilityIT extends AbstractIntegrationTest {

  @Autowired private ProfileLockPreviewService previews;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileCastSupport cast;

  private final List<TestIdentity> identities = new ArrayList<>();
  private TestIdentity smith;
  private TestIdentity cabin;

  @BeforeEach
  void setUp() {
    smith = track(authTestSupport.createIdentity());
    cabin = track(authTestSupport.createIdentity());
  }

  @AfterEach
  void tearDown() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should say others would lock without naming them when the caller cannot see the Profile in"
          + " the Household")
  void shouldSayOthersWouldLockWithoutNamingThemWhenTheCallerCannotSeeTheProfileInTheHousehold() {
    var sam =
        cast.profileManagedBy(
            cabin, ProfileFixture.kidProfileBuilder().name("Sam").maximumAllowedRatingAge(13));
    cast.makeAvailable(sam, smith);
    var mara =
        cast.profileManagedBy(
            smith, ProfileFixture.kidProfileBuilder().name("Mara").maximumAllowedRatingAge(10));
    // The caller manages Pat, who would lock in the cabin too, but may not see Sam there.
    var pat =
        cast.profileManagedBy(
            smith, ProfileFixture.kidProfileBuilder().name("Pat").maximumAllowedRatingAge(10));
    cast.makeAvailable(pat, cabin);

    var preview =
        previews
            .policyChangePreview(
                authTestSupport.identityOf(smith),
                sam.getId(),
                new ProfilePolicyTarget(ProfileKind.KID, 7))
            .orElseThrow();

    assertThat(preview.households())
        .extracting(
            locks -> locks.household().getId(),
            locks -> locks.profiles().stream().map(Profile::getId).toList())
        .as("the Household the caller uses, without the cabin")
        .containsExactly(tuple(smith.household().getId(), List.of(mara.getId(), pat.getId())));
    assertThat(preview.otherProfilesWillLock()).as("Pat in the cabin, unnamed").isTrue();
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
