package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Tag("UnitTest")
@DisplayName("Access Token Issuer Tests")
class AccessTokenIssuerTest {

  private static final String TEST_KEY_BASE64 =
      "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQga+ZKCbAcyZIb7k2FE8rMPFtIpTdzX2dR/csZ8k6A95uhRANCAAQawOmVKMDLAOsboxKLb9khGsWyxwcIikucXDCfX18ME5X9/kqSS2vdMnFfZ6KR12U/Sy/EwOwnc82xFAyFdNbe";

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
          cryptoConfig.jwtEncoder(cryptoConfig.tokenSigningKeys(properties)),
          properties,
          Clock.systemUTC(),
          membershipRepository,
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
    membershipRepository.grantMembership(
        HouseholdMembership.builder()
            .accountId(account.getId())
            .householdId(householdId)
            .householdRole(HouseholdRole.OWNER)
            .build());
    var profile =
        profileRepository.save(
            ProfileFixture.defaultProfileBuilder().householdId(householdId).build());
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
    assertThat(decoded.getClaimAsString(JwtClaimNames.ISS)).isEqualTo(TokenContract.ISSUER);
    assertThat(decoded.getSubject()).isEqualTo(account.getId().toString());
    assertThat(decoded.getClaimAsString(TokenClaims.SESSION_ID))
        .isEqualTo(session.getId().toString());
    assertThat(decoded.getClaimAsString(TokenClaims.SCOPE)).isEqualTo("profile");
    assertThat(decoded.getClaimAsString(TokenClaims.HOUSEHOLD_ID))
        .isEqualTo(householdId.toString());
    assertThat(decoded.getClaimAsString(TokenClaims.HOUSEHOLD_ROLE)).isEqualTo("OWNER");
    assertThat(decoded.getClaimAsString(TokenClaims.PROFILE_ID))
        .isEqualTo(profile.getId().toString());
    assertThat(decoded.getClaims()).doesNotContainKeys("sv", "mv", "pv");
    assertThat(decoded.getClaimAsString(TokenClaims.ROLE)).isEqualTo("USER");
    assertThat(Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()))
        .isEqualTo(properties.accessTokenTtl());
    assertThat(token.expiresAt()).isEqualTo(decoded.getExpiresAt());
  }

  @Test
  @DisplayName("Should reject profile when outside household")
  void shouldRejectProfileWhenOutsideHousehold() {
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
  @DisplayName("Should cap derived expiry when source expires sooner")
  void shouldCapDerivedExpiryWhenSourceExpiresSooner() {
    var now = Instant.parse("2026-07-10T12:00:00Z");
    var fixedIssuer = issuerAt(now);
    var sourceExpiry = now.plus(Duration.ofMinutes(3));

    var token = fixedIssuer.issueDerived(accountContext(), sourceExpiry);

    assertThat(token.expiresAt()).isEqualTo(sourceExpiry);
  }

  @Test
  @DisplayName("Should use configured ttl when source expires later")
  void shouldUseConfiguredTtlWhenSourceExpiresLater() {
    var now = Instant.parse("2026-07-10T12:00:00Z");
    var fixedIssuer = issuerAt(now);
    var sourceExpiry = now.plus(Duration.ofMinutes(30));

    var token = fixedIssuer.issueDerived(accountContext(), sourceExpiry);

    // A derived token never outlives its source, but a source with generous remaining
    // lifetime still yields only the configured TTL.
    assertThat(token.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(10)));
  }

  private AccessTokenIssuer issuerAt(Instant now) {
    return new AccessTokenIssuer(
        cryptoConfig.jwtEncoder(cryptoConfig.tokenSigningKeys(properties)),
        properties,
        Clock.fixed(now, ZoneOffset.UTC),
        membershipRepository,
        accountProfileRepository);
  }

  private TokenContext accountContext() {
    var account = AccountFixture.defaultAccountBuilder().id(UUID.randomUUID()).build();
    var session =
        AuthSession.builder()
            .id(UUID.randomUUID())
            .accountId(account.getId())
            .sessionVersion(1)
            .build();
    return TokenContext.builder().account(account).session(session).build();
  }

  @Test
  @DisplayName("Should issue household scoped token when membership exists")
  void shouldIssueHouseholdScopedTokenWhenMembershipExists() {
    var account = AccountFixture.defaultAccountBuilder().id(UUID.randomUUID()).build();
    var session = AuthSession.builder().id(UUID.randomUUID()).accountId(account.getId()).build();
    var householdId = UUID.randomUUID();
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
    assertThat(decoded.getClaims()).doesNotContainKey("mv");
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

  private NimbusJwtDecoder buildDecoder() {
    var keys = cryptoConfig.tokenSigningKeys(properties);
    var processor = new DefaultJWTProcessor<SecurityContext>();
    processor.setJWSKeySelector(
        new JWSVerificationKeySelector<>(
            JWSAlgorithm.ES256, new ImmutableJWKSet<>(keys.verificationKeys())));
    processor.setJWTClaimsSetVerifier((claims, context) -> {});
    return new NimbusJwtDecoder(processor);
  }
}
