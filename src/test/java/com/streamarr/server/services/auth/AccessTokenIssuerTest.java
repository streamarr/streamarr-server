package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.TokenCryptoConfig;
import com.streamarr.server.domain.auth.AccountProfile;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.exceptions.HouseholdAccessDeniedException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.fakes.FakeAccountProfileRepository;
import com.streamarr.server.fakes.FakeHouseholdMembershipRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Tag("UnitTest")
@DisplayName("Access Token Issuer Tests")
class AccessTokenIssuerTest {

  private static final String TEST_KEY_BASE64 = "dGVzdC1zaWduaW5nLWtleS0zMi1ieXRlcy1sb25nISE=";

  private final AuthTokenProperties properties =
      AuthTokenProperties.builder()
          .signingKey(TEST_KEY_BASE64)
          .accessTokenTtl(Duration.ofMinutes(10))
          .refreshTokenTtl(Duration.ofDays(30))
          .rotationGrace(Duration.ofSeconds(30))
          .build();

  private final FakeHouseholdMembershipRepository membershipRepository =
      new FakeHouseholdMembershipRepository();
  private final FakeProfileRepository profileRepository = new FakeProfileRepository();
  private final FakeAccountProfileRepository accountProfileRepository =
      new FakeAccountProfileRepository(membershipRepository);

  private final TokenCryptoConfig cryptoConfig = new TokenCryptoConfig();

  private final AccessTokenIssuer issuer =
      new AccessTokenIssuer(
          cryptoConfig.jwtEncoder(cryptoConfig.authSigningKey(properties)),
          properties,
          Clock.systemUTC(),
          membershipRepository,
          profileRepository,
          accountProfileRepository);

  @Test
  @DisplayName("Should nest scopes when issuing profile token")
  void shouldNestScopesWhenIssuingProfileToken() {
    var account = AccountFixture.defaultAccountBuilder().id(UUID.randomUUID()).build();
    var session =
        AuthSession.builder()
            .id(UUID.randomUUID())
            .accountId(account.getId())
            .sessionVersion(7)
            .build();
    var householdId = UUID.randomUUID();
    var membership =
        membershipRepository.grantMembership(
            HouseholdMembership.builder()
                .accountId(account.getId())
                .householdId(householdId)
                .householdRole(HouseholdRole.OWNER)
                .build());
    var profile =
        profileRepository.save(
            ProfileFixture.defaultProfileBuilder()
                .householdId(householdId)
                .policyVersion(5)
                .build());
    accountProfileRepository.save(
        AccountProfile.builder()
            .accountId(account.getId())
            .householdId(householdId)
            .profileId(profile.getId())
            .build());

    var token =
        issuer.issue(
            TokenContext.builder()
                .account(account)
                .session(session)
                .householdId(householdId)
                .profileId(profile.getId())
                .build());

    assertThat(token.scope()).isEqualTo(TokenScope.PROFILE);

    var decoded = buildDecoder().decode(token.value());
    assertThat(decoded.getSubject()).isEqualTo(account.getId().toString());
    assertThat(decoded.getClaimAsString(TokenClaims.SESSION_ID))
        .isEqualTo(session.getId().toString());
    assertThat(decoded.<Long>getClaim(TokenClaims.SESSION_VERSION)).isEqualTo(7L);
    assertThat(decoded.getClaimAsString(TokenClaims.SCOPE)).isEqualTo("profile");
    assertThat(decoded.getClaimAsString(TokenClaims.HOUSEHOLD_ID))
        .isEqualTo(householdId.toString());
    assertThat(decoded.getClaimAsString(TokenClaims.HOUSEHOLD_ROLE)).isEqualTo("OWNER");
    assertThat(decoded.<Long>getClaim(TokenClaims.MEMBERSHIP_VERSION))
        .isEqualTo(membership.version());
    assertThat(decoded.getClaimAsString(TokenClaims.PROFILE_ID))
        .isEqualTo(profile.getId().toString());
    assertThat(decoded.<Long>getClaim(TokenClaims.POLICY_VERSION)).isEqualTo(5L);
    assertThat(decoded.getClaimAsString(TokenClaims.ROLE)).isEqualTo("USER");
    assertThat(Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()))
        .isEqualTo(properties.accessTokenTtl());
    assertThat(token.expiresAt()).isEqualTo(decoded.getExpiresAt());
  }

  @Test
  @DisplayName("Should reject profile outside household")
  void shouldRejectProfileOutsideHousehold() {
    var account = AccountFixture.defaultAccountBuilder().id(UUID.randomUUID()).build();
    var session = AuthSession.builder().id(UUID.randomUUID()).accountId(account.getId()).build();
    var memberHouseholdId = UUID.randomUUID();
    var otherHouseholdId = UUID.randomUUID();
    membershipRepository.grantMembership(
        HouseholdMembership.builder()
            .accountId(account.getId())
            .householdId(memberHouseholdId)
            .householdRole(HouseholdRole.OWNER)
            .build());
    var foreignProfile =
        profileRepository.save(
            ProfileFixture.defaultProfileBuilder().householdId(otherHouseholdId).build());
    accountProfileRepository.save(
        AccountProfile.builder()
            .accountId(account.getId())
            .householdId(otherHouseholdId)
            .profileId(foreignProfile.getId())
            .build());

    var context =
        TokenContext.builder()
            .account(account)
            .session(session)
            .householdId(memberHouseholdId)
            .profileId(foreignProfile.getId())
            .build();

    assertThatThrownBy(() -> issuer.issue(context))
        .isInstanceOf(ProfileAccessDeniedException.class);
  }

  @Test
  @DisplayName("Should issue account scoped token when no context selected")
  void shouldIssueAccountScopedTokenWhenNoContextSelected() {
    var account = AccountFixture.defaultAccountBuilder().id(UUID.randomUUID()).build();
    var session =
        AuthSession.builder()
            .id(UUID.randomUUID())
            .accountId(account.getId())
            .sessionVersion(2)
            .build();

    var token = issuer.issue(TokenContext.builder().account(account).session(session).build());

    assertThat(token.scope()).isEqualTo(TokenScope.ACCOUNT);

    var decoded = buildDecoder().decode(token.value());
    assertThat(decoded.getClaimAsString(TokenClaims.SCOPE)).isEqualTo("account");
    assertThat(decoded.getClaimAsString(TokenClaims.HOUSEHOLD_ID)).isNull();
    assertThat(decoded.getClaimAsString(TokenClaims.PROFILE_ID)).isNull();
  }

  @Test
  @DisplayName("Should issue household scoped token when membership exists")
  void shouldIssueHouseholdScopedTokenWhenMembershipExists() {
    var account = AccountFixture.defaultAccountBuilder().id(UUID.randomUUID()).build();
    var session = AuthSession.builder().id(UUID.randomUUID()).accountId(account.getId()).build();
    var householdId = UUID.randomUUID();
    var membership =
        membershipRepository.grantMembership(
            HouseholdMembership.builder()
                .accountId(account.getId())
                .householdId(householdId)
                .householdRole(HouseholdRole.PARENT)
                .build());

    var token =
        issuer.issue(
            TokenContext.builder()
                .account(account)
                .session(session)
                .householdId(householdId)
                .build());

    assertThat(token.scope()).isEqualTo(TokenScope.HOUSEHOLD);

    var decoded = buildDecoder().decode(token.value());
    assertThat(decoded.getClaimAsString(TokenClaims.SCOPE)).isEqualTo("household");
    assertThat(decoded.getClaimAsString(TokenClaims.HOUSEHOLD_ID))
        .isEqualTo(householdId.toString());
    assertThat(decoded.getClaimAsString(TokenClaims.HOUSEHOLD_ROLE)).isEqualTo("PARENT");
    assertThat(decoded.<Long>getClaim(TokenClaims.MEMBERSHIP_VERSION))
        .isEqualTo(membership.version());
    assertThat(decoded.getClaimAsString(TokenClaims.PROFILE_ID)).isNull();
  }

  @Test
  @DisplayName("Should reject household token when account not member")
  void shouldRejectHouseholdTokenWhenAccountNotMember() {
    var account = AccountFixture.defaultAccountBuilder().id(UUID.randomUUID()).build();
    var session = AuthSession.builder().id(UUID.randomUUID()).accountId(account.getId()).build();

    var context =
        TokenContext.builder()
            .account(account)
            .session(session)
            .householdId(UUID.randomUUID())
            .build();

    assertThatThrownBy(() -> issuer.issue(context))
        .isInstanceOf(HouseholdAccessDeniedException.class);
  }

  @Test
  @DisplayName("Should reject profile context when household not selected")
  void shouldRejectProfileContextWhenHouseholdNotSelected() {
    var account = AccountFixture.defaultAccountBuilder().id(UUID.randomUUID()).build();
    var session = AuthSession.builder().id(UUID.randomUUID()).accountId(account.getId()).build();
    var profileId = UUID.randomUUID();
    var contextBuilder =
        TokenContext.builder().account(account).session(session).profileId(profileId);

    assertThatThrownBy(contextBuilder::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Profile context requires a household");
  }

  @Test
  @DisplayName("Should reject context when account missing")
  void shouldRejectContextWhenAccountMissing() {
    var session = AuthSession.builder().id(UUID.randomUUID()).accountId(UUID.randomUUID()).build();
    var contextBuilder = TokenContext.builder().session(session);

    assertThatThrownBy(contextBuilder::build)
        .isInstanceOf(NullPointerException.class)
        .hasMessage("account");
  }

  @Test
  @DisplayName("Should reject context when session missing")
  void shouldRejectContextWhenSessionMissing() {
    var account = AccountFixture.defaultAccountBuilder().id(UUID.randomUUID()).build();
    var contextBuilder = TokenContext.builder().account(account);

    assertThatThrownBy(contextBuilder::build)
        .isInstanceOf(NullPointerException.class)
        .hasMessage("session");
  }

  @Test
  @DisplayName("Should reject profile token when profile row missing")
  void shouldRejectProfileTokenWhenProfileRowMissing() {
    var account = AccountFixture.defaultAccountBuilder().id(UUID.randomUUID()).build();
    var session = AuthSession.builder().id(UUID.randomUUID()).accountId(account.getId()).build();
    var householdId = UUID.randomUUID();
    var missingProfileId = UUID.randomUUID();
    membershipRepository.grantMembership(
        HouseholdMembership.builder()
            .accountId(account.getId())
            .householdId(householdId)
            .householdRole(HouseholdRole.OWNER)
            .build());
    accountProfileRepository.save(
        AccountProfile.builder()
            .accountId(account.getId())
            .householdId(householdId)
            .profileId(missingProfileId)
            .build());

    var context =
        TokenContext.builder()
            .account(account)
            .session(session)
            .householdId(householdId)
            .profileId(missingProfileId)
            .build();

    assertThatThrownBy(() -> issuer.issue(context))
        .isInstanceOf(ProfileAccessDeniedException.class);
  }

  private NimbusJwtDecoder buildDecoder() {
    var keyBytes = java.util.Base64.getDecoder().decode(TEST_KEY_BASE64);
    return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(keyBytes, "HmacSHA256"))
        .macAlgorithm(MacAlgorithm.HS256)
        .build();
  }
}
