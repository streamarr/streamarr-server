package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.ServerBootstrap.SERVER_BOOTSTRAP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountProfile;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.RefreshToken;
import com.streamarr.server.domain.auth.RefreshTokenStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
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
  @DisplayName("Should reject duplicate email when casing differs")
  void shouldRejectDuplicateEmailWhenCasingDiffers() {
    userAccountRepository.save(
        AccountFixture.defaultAccountBuilder().email("Casing@Example.com").build());

    var duplicate = AccountFixture.defaultAccountBuilder().email("casing@example.com").build();

    assertThatThrownBy(() -> userAccountRepository.save(duplicate))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_user_account_email");
  }

  @Test
  @DisplayName("Should cascade account profile link when membership revoked")
  void shouldCascadeAccountProfileLinkWhenMembershipRevoked() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var membership =
        grantMembership(
            HouseholdMembership.builder()
                .accountId(account.getId())
                .householdId(household.getId())
                .householdRole(HouseholdRole.OWNER)
                .build());
    var profile =
        profileRepository.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    accountProfileRepository.linkProfile(
        AccountProfile.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .profileId(profile.getId())
            .build());
    assertThat(
            accountProfileRepository.findByAccountIdAndProfileId(account.getId(), profile.getId()))
        .isPresent();

    householdMembershipRepository.revokeMembership(account.getId(), household.getId());

    assertThat(
            accountProfileRepository.findByAccountIdAndProfileId(account.getId(), profile.getId()))
        .isEmpty();
    assertThat(profileRepository.findById(profile.getId())).isPresent();
    assertThat(userAccountRepository.findById(account.getId())).isPresent();
  }

  @Test
  @DisplayName("Should reject account profile link when profile belongs to other household")
  void shouldRejectAccountProfileLinkWhenProfileBelongsToOtherHousehold() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var memberHousehold =
        householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    grantMembership(
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

    assertThatThrownBy(() -> accountProfileRepository.linkProfile(link))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_account_profile_profile");
  }

  @Test
  @DisplayName("Should reject session active profile when profile belongs to other household")
  void shouldRejectSessionActiveProfileWhenProfileBelongsToOtherHousehold() {
    var seeded = seedLinkedIdentity();
    var otherHousehold =
        householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    grantMembership(
        HouseholdMembership.builder()
            .accountId(seeded.account().getId())
            .householdId(otherHousehold.getId())
            .householdRole(HouseholdRole.MEMBER)
            .build());

    var session =
        AuthSession.builder()
            .accountId(seeded.account().getId())
            .activeHouseholdId(otherHousehold.getId())
            .activeProfileId(seeded.profile().getId())
            .build();

    assertThatThrownBy(() -> authSessionRepository.save(session))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_auth_session_active_profile_household");
  }

  @Test
  @DisplayName("Should reject session household when account not member")
  void shouldRejectSessionHouseholdWhenAccountNotMember() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());

    var session =
        AuthSession.builder()
            .accountId(account.getId())
            .activeHouseholdId(household.getId())
            .build();

    assertThatThrownBy(() -> authSessionRepository.save(session))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_auth_session_active_membership");
  }

  @Test
  @DisplayName("Should reject session profile when account not linked")
  void shouldRejectSessionProfileWhenAccountNotLinked() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    grantMembership(
        HouseholdMembership.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .householdRole(HouseholdRole.MEMBER)
            .build());
    var unlinkedProfile =
        profileRepository.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());

    var session =
        AuthSession.builder()
            .accountId(account.getId())
            .activeHouseholdId(household.getId())
            .activeProfileId(unlinkedProfile.getId())
            .build();

    assertThatThrownBy(() -> authSessionRepository.save(session))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_auth_session_active_account_profile");
  }

  @Test
  @DisplayName("Should allow session profile when household not selected")
  void shouldAllowSessionProfileWhenHouseholdNotSelected() {
    // Pins a deliberate schema gap: a CHECK forbidding this state would fail the
    // membership/household delete cascades, which clear the two columns in separate
    // statements. The profile-requires-household pairing is an application-layer rule.
    var seeded = seedLinkedIdentity();

    var session =
        authSessionRepository.save(
            AuthSession.builder()
                .accountId(seeded.account().getId())
                .activeProfileId(seeded.profile().getId())
                .build());

    var reloaded = authSessionRepository.findById(session.getId()).orElseThrow();
    assertThat(reloaded.getActiveProfileId()).isEqualTo(seeded.profile().getId());
    assertThat(reloaded.getActiveHouseholdId()).isNull();
  }

  @Test
  @DisplayName("Should reject session revocation reason when timestamp missing")
  void shouldRejectSessionRevocationReasonWhenTimestampMissing() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());

    var session =
        AuthSession.builder()
            .accountId(account.getId())
            .revokedReason(SessionRevocationReason.LOGOUT)
            .build();

    assertThatThrownBy(() -> authSessionRepository.save(session))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_auth_session_revocation_pair");
  }

  @Test
  @DisplayName("Should reject session revocation timestamp when reason missing")
  void shouldRejectSessionRevocationTimestampWhenReasonMissing() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());

    var session = AuthSession.builder().accountId(account.getId()).revokedAt(Instant.now()).build();

    assertThatThrownBy(() -> authSessionRepository.save(session))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_auth_session_revocation_pair");
  }

  @Test
  @DisplayName("Should persist every revocation reason when session revoked")
  void shouldPersistEveryRevocationReasonWhenSessionRevoked() {
    // Round-trips each Java constant through the Postgres enum, so a one-sided
    // addition to either type fails here instead of in a later consumer.
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());

    for (var reason : SessionRevocationReason.values()) {
      var session =
          authSessionRepository.save(
              AuthSession.builder()
                  .accountId(account.getId())
                  .revokedAt(Instant.now())
                  .revokedReason(reason)
                  .build());

      var reloaded = authSessionRepository.findById(session.getId()).orElseThrow();
      assertThat(reloaded.getRevokedReason()).isEqualTo(reason);
    }
  }

  @Test
  @DisplayName("Should downgrade session to household scope when profile link revoked")
  void shouldDowngradeSessionToHouseholdScopeWhenProfileLinkRevoked() {
    var seeded = seedLinkedIdentity();
    var session =
        authSessionRepository.save(
            AuthSession.builder()
                .accountId(seeded.account().getId())
                .activeHouseholdId(seeded.household().getId())
                .activeProfileId(seeded.profile().getId())
                .build());

    accountProfileRepository.revokeProfileLink(
        AccountProfile.builder()
            .accountId(seeded.account().getId())
            .householdId(seeded.household().getId())
            .profileId(seeded.profile().getId())
            .build());

    var reloaded = authSessionRepository.findById(session.getId()).orElseThrow();
    assertThat(reloaded.getActiveProfileId()).isNull();
    assertThat(reloaded.getActiveHouseholdId()).isEqualTo(seeded.household().getId());
  }

  @Test
  @DisplayName("Should clear active selections when membership revoked")
  void shouldClearActiveSelectionsWhenMembershipRevoked() {
    var seeded = seedLinkedIdentity();
    var session =
        authSessionRepository.save(
            AuthSession.builder()
                .accountId(seeded.account().getId())
                .activeHouseholdId(seeded.household().getId())
                .activeProfileId(seeded.profile().getId())
                .build());

    householdMembershipRepository.revokeMembership(
        seeded.account().getId(), seeded.household().getId());

    var reloaded = authSessionRepository.findById(session.getId()).orElseThrow();
    assertThat(reloaded.getActiveHouseholdId()).isNull();
    assertThat(reloaded.getActiveProfileId()).isNull();
  }

  @Test
  @DisplayName("Should keep household scope when profile deleted")
  void shouldKeepHouseholdScopeWhenProfileDeleted() {
    var seeded = seedLinkedIdentity();
    var session =
        authSessionRepository.save(
            AuthSession.builder()
                .accountId(seeded.account().getId())
                .activeHouseholdId(seeded.household().getId())
                .activeProfileId(seeded.profile().getId())
                .build());

    profileRepository.delete(seeded.profile());

    var reloaded = authSessionRepository.findById(session.getId()).orElseThrow();
    assertThat(reloaded.getActiveProfileId()).isNull();
    assertThat(reloaded.getActiveHouseholdId()).isEqualTo(seeded.household().getId());
  }

  @Test
  @DisplayName("Should cascade sessions and refresh tokens when account deleted")
  void shouldCascadeSessionsAndRefreshTokensWhenAccountDeleted() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var session =
        authSessionRepository.save(
            AuthSession.builder().accountId(account.getId()).deviceName("test-device").build());
    var token =
        refreshTokenRepository.save(tokenBuilder(session, RefreshTokenStatus.ACTIVE).build());

    userAccountRepository.delete(account);

    assertThat(authSessionRepository.findById(session.getId())).isEmpty();
    assertThat(refreshTokenRepository.findById(token.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should clear active selections when household deleted")
  void shouldClearActiveSelectionsWhenHouseholdDeleted() {
    var seeded = seedLinkedIdentity();
    var session =
        authSessionRepository.save(
            AuthSession.builder()
                .accountId(seeded.account().getId())
                .activeHouseholdId(seeded.household().getId())
                .activeProfileId(seeded.profile().getId())
                .build());

    householdRepository.delete(seeded.household());

    var reloaded = authSessionRepository.findById(session.getId()).orElseThrow();
    assertThat(reloaded.getActiveHouseholdId()).isNull();
    assertThat(reloaded.getActiveProfileId()).isNull();
  }

  private record LinkedIdentity(
      UserAccount account, Household household, HouseholdMembership membership, Profile profile) {}

  private LinkedIdentity seedLinkedIdentity() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var membership =
        grantMembership(
            HouseholdMembership.builder()
                .accountId(account.getId())
                .householdId(household.getId())
                .householdRole(HouseholdRole.OWNER)
                .build());
    var profile =
        profileRepository.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    accountProfileRepository.linkProfile(
        AccountProfile.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .profileId(profile.getId())
            .build());
    return new LinkedIdentity(account, household, membership, profile);
  }

  @Test
  @DisplayName("Should reject bootstrap claim when already claimed")
  void shouldRejectBootstrapClaimWhenAlreadyClaimed() {
    var admin =
        userAccountRepository.save(
            AccountFixture.defaultAccountBuilder().accountRole(AccountRole.ADMIN).build());

    var firstClaim = serverBootstrapRepository.claim(admin.getId());
    var secondClaim = serverBootstrapRepository.claim(admin.getId());

    assertThat(firstClaim).isTrue();
    assertThat(secondClaim).isFalse();
  }

  @Test
  @DisplayName("Should preserve winning admin when second bootstrap claim loses")
  void shouldPreserveWinningAdminWhenSecondBootstrapClaimLoses() {
    var winner =
        userAccountRepository.save(
            AccountFixture.defaultAccountBuilder().accountRole(AccountRole.ADMIN).build());
    var loser =
        userAccountRepository.save(
            AccountFixture.defaultAccountBuilder().accountRole(AccountRole.ADMIN).build());

    serverBootstrapRepository.claim(winner.getId());
    var losingClaim = serverBootstrapRepository.claim(loser.getId());

    assertThat(losingClaim).isFalse();
    var claimedAdminId =
        dsl.select(SERVER_BOOTSTRAP.ADMIN_ACCOUNT_ID)
            .from(SERVER_BOOTSTRAP)
            .fetchOne(SERVER_BOOTSTRAP.ADMIN_ACCOUNT_ID);
    assertThat(claimedAdminId).isEqualTo(winner.getId());
  }

  @Test
  @DisplayName("Should reject active refresh token when session already has one")
  void shouldRejectActiveRefreshTokenWhenSessionAlreadyHasOne() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var session =
        authSessionRepository.save(
            AuthSession.builder().accountId(account.getId()).deviceName("test-device").build());

    refreshTokenRepository.save(tokenBuilder(session, RefreshTokenStatus.ACTIVE).build());
    refreshTokenRepository.save(
        tokenBuilder(session, RefreshTokenStatus.ROTATED).rotatedAt(Instant.now()).build());

    var secondActive = tokenBuilder(session, RefreshTokenStatus.ACTIVE).build();

    assertThatThrownBy(() -> refreshTokenRepository.save(secondActive))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_refresh_token_active_session");
  }

  @Test
  @DisplayName("Should reject rotated token when rotation timestamp missing")
  void shouldRejectRotatedTokenWhenRotationTimestampMissing() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var session =
        authSessionRepository.save(
            AuthSession.builder().accountId(account.getId()).deviceName("test-device").build());

    var rotatedWithoutTimestamp = tokenBuilder(session, RefreshTokenStatus.ROTATED).build();

    assertThatThrownBy(() -> refreshTokenRepository.save(rotatedWithoutTimestamp))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_refresh_token_rotated_at");
  }

  private RefreshToken.RefreshTokenBuilder<?, ?> tokenBuilder(
      AuthSession session, RefreshTokenStatus status) {
    return RefreshToken.builder()
        .sessionId(session.getId())
        .digest("digest-" + UUID.randomUUID())
        .status(status)
        .expiresAt(Instant.now().plus(Duration.ofDays(30)));
  }

  private HouseholdMembership grantMembership(HouseholdMembership membership) {
    householdMembershipRepository.grantMembership(membership);
    return householdMembershipRepository
        .findByAccountIdAndHouseholdId(membership.getAccountId(), membership.getHouseholdId())
        .orElseThrow();
  }
}
