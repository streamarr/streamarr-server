package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.ServerBootstrap.SERVER_BOOTSTRAP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountProfile;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.RefreshToken;
import com.streamarr.server.domain.auth.RefreshTokenStatus;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@Tag("IntegrationTest")
@DisplayName("Identity Schema Integration Tests")
class IdentitySchemaIT extends AbstractIntegrationTest {

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private HouseholdRepository householdRepository;

  @Autowired private HouseholdMembershipRepository householdMembershipRepository;

  @Autowired private ProfileRepository profileRepository;

  @Autowired private AccountProfileRepository accountProfileRepository;

  @Autowired private ServerBootstrapRepository serverBootstrapRepository;

  @Autowired private AuthSessionRepository authSessionRepository;

  @Autowired private RefreshTokenRepository refreshTokenRepository;

  @Autowired private DSLContext dsl;

  @AfterEach
  void releaseBootstrapClaim() {
    // The reusable container outlives each run; the singleton claim must never leak between
    // tests.
    dsl.deleteFrom(SERVER_BOOTSTRAP).execute();
  }

  @Test
  @DisplayName("Should cascade account profile link when membership revoked")
  void shouldCascadeAccountProfileLinkWhenMembershipRevoked() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var membership =
        householdMembershipRepository.save(
            HouseholdMembership.builder()
                .accountId(account.getId())
                .householdId(household.getId())
                .householdRole(HouseholdRole.OWNER)
                .build());
    var profile =
        profileRepository.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    var link =
        accountProfileRepository.save(
            AccountProfile.builder()
                .accountId(account.getId())
                .householdId(household.getId())
                .profileId(profile.getId())
                .build());

    householdMembershipRepository.delete(membership);

    assertThat(accountProfileRepository.findById(link.getId())).isEmpty();
    assertThat(profileRepository.findById(profile.getId())).isPresent();
    assertThat(userAccountRepository.findById(account.getId())).isPresent();
  }

  @Test
  @DisplayName("Should reject account profile link when profile belongs to other household")
  void shouldRejectAccountProfileLinkWhenProfileBelongsToOtherHousehold() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var memberHousehold =
        householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    householdMembershipRepository.save(
        HouseholdMembership.builder()
            .accountId(account.getId())
            .householdId(memberHousehold.getId())
            .householdRole(HouseholdRole.OWNER)
            .build());
    var otherHousehold =
        householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var foreignProfile =
        profileRepository.save(
            ProfileFixture.defaultProfileBuilder().householdId(otherHousehold.getId()).build());

    var link =
        AccountProfile.builder()
            .accountId(account.getId())
            .householdId(memberHousehold.getId())
            .profileId(foreignProfile.getId())
            .build();

    assertThatThrownBy(() -> accountProfileRepository.save(link))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("Should reject session active profile from other household")
  void shouldRejectSessionActiveProfileFromOtherHousehold() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var otherHousehold =
        householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var profile =
        profileRepository.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());

    var session =
        AuthSession.builder()
            .accountId(account.getId())
            .activeHouseholdId(otherHousehold.getId())
            .activeProfileId(profile.getId())
            .build();

    assertThatThrownBy(() -> authSessionRepository.save(session))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("Should clear active selections when household deleted")
  void shouldClearActiveSelectionsWhenHouseholdDeleted() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var profile =
        profileRepository.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    var session =
        authSessionRepository.save(
            AuthSession.builder()
                .accountId(account.getId())
                .activeHouseholdId(household.getId())
                .activeProfileId(profile.getId())
                .build());

    householdRepository.delete(household);

    var reloaded = authSessionRepository.findById(session.getId()).orElseThrow();
    assertThat(reloaded.getActiveHouseholdId()).isNull();
    assertThat(reloaded.getActiveProfileId()).isNull();
  }

  @Test
  @DisplayName("Should reject second bootstrap claim")
  void shouldRejectSecondBootstrapClaim() {
    var admin =
        userAccountRepository.save(
            AccountFixture.defaultAccountBuilder().accountRole(AccountRole.ADMIN).build());

    var firstClaim = serverBootstrapRepository.claim(admin.getId());
    var secondClaim = serverBootstrapRepository.claim(admin.getId());

    assertThat(firstClaim).isTrue();
    assertThat(secondClaim).isFalse();
  }

  @Test
  @DisplayName("Should reject second active refresh token per session")
  void shouldRejectSecondActiveRefreshTokenPerSession() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var session =
        authSessionRepository.save(
            AuthSession.builder().accountId(account.getId()).deviceName("test-device").build());

    refreshTokenRepository.save(tokenBuilder(session, RefreshTokenStatus.ACTIVE).build());
    refreshTokenRepository.save(tokenBuilder(session, RefreshTokenStatus.ROTATED).build());

    var secondActive = tokenBuilder(session, RefreshTokenStatus.ACTIVE).build();

    assertThatThrownBy(() -> refreshTokenRepository.save(secondActive))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private RefreshToken.RefreshTokenBuilder<?, ?> tokenBuilder(
      AuthSession session, RefreshTokenStatus status) {
    return RefreshToken.builder()
        .sessionId(session.getId())
        .digest("digest-" + UUID.randomUUID())
        .status(status)
        .expiresAt(Instant.now().plus(Duration.ofDays(30)));
  }
}
