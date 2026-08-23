package com.streamarr.server.services.auth;

import static com.streamarr.server.support.TokenTestSupport.TEST_SIGNING_KEY;
import static com.streamarr.server.support.TokenTestSupport.decoder;
import static com.streamarr.server.support.TokenTestSupport.tokenProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.TokenCryptoConfig;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Tag("UnitTest")
@DisplayName("Access Token Issuer Tests")
class AccessTokenIssuerTest {

  private final AuthTokenProperties properties = tokenProperties();
  private final TokenCryptoConfig cryptoConfig = new TokenCryptoConfig();
  private final AccessTokenIssuer issuer = issuerAt(Clock.systemUTC());

  @Test
  @DisplayName("Should mint the configured issuer when one is provided")
  void shouldMintConfiguredIssuerWhenOneIsProvided() {
    var urlIssuerProperties =
        AuthTokenProperties.builder()
            .signingKey(TEST_SIGNING_KEY)
            .issuer("https://auth.example.test")
            .accessTokenTtl(Duration.ofMinutes(10))
            .refreshTokenTtl(Duration.ofDays(30))
            .rotationGrace(Duration.ofSeconds(30))
            .build();
    var urlIssuer =
        new AccessTokenIssuer(
            cryptoConfig.jwtEncoder(cryptoConfig.tokenSigningKeys(urlIssuerProperties)),
            urlIssuerProperties,
            Clock.systemUTC());

    var token = urlIssuer.issue(accountContext(account()));

    var decoded = buildDecoder().decode(token.value());
    assertThat(decoded.getClaimAsString(JwtClaimNames.ISS)).isEqualTo("https://auth.example.test");
  }

  @Test
  @DisplayName("Should carry every signed fact when issuing a profile token")
  void shouldCarryEverySignedFactWhenIssuingProfileToken() {
    var account =
        account().toBuilder().serverAdmin(true).householdRole(HouseholdRole.ADMIN).build();
    var session = session(account);
    var visitedHouseholdId = UUID.randomUUID();
    var profileId = UUID.randomUUID();

    var token =
        issuer.issue(
            TokenContext.builder()
                .account(account)
                .session(session)
                .contextHouseholdId(visitedHouseholdId)
                .profileId(Optional.of(profileId))
                .build());

    assertThat(token.scope()).isEqualTo(TokenScope.PROFILE);
    var decoded = buildDecoder().decode(token.value());
    assertThat(decoded.getClaimAsString(JwtClaimNames.ISS)).isEqualTo("streamarr");
    assertThat(decoded.getAudience()).containsExactly("streamarr");
    assertThat(decoded.getSubject()).isEqualTo(account.getId().toString());
    assertThat(decoded.getClaimAsString(TokenClaims.SESSION_ID))
        .isEqualTo(session.getId().toString());
    assertThat(decoded.getClaimAsString(TokenClaims.SCOPE)).isEqualTo("profile");
    assertThat(decoded.getClaimAsString(TokenClaims.HOUSEHOLD_ID))
        .isEqualTo(account.getHouseholdId().toString());
    assertThat(decoded.getClaimAsString(TokenClaims.HOUSEHOLD_ROLE)).isEqualTo("ADMIN");
    assertThat(decoded.getClaims()).doesNotContainKey("sa");
    assertThat(decoded.getClaimAsString(TokenClaims.CONTEXT_HOUSEHOLD_ID))
        .isEqualTo(visitedHouseholdId.toString());
    assertThat(decoded.getClaimAsString(TokenClaims.PROFILE_ID)).isEqualTo(profileId.toString());
    assertThat(decoded.getClaims()).doesNotContainKeys("roles", "reauthenticated_at");
    assertThat(Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()))
        .isEqualTo(properties.accessTokenTtl());
    assertThat(token.expiresAt()).isEqualTo(decoded.getExpiresAt());
  }

  @Test
  @DisplayName("Should issue an account scoped token at the picker when no profile is selected")
  void shouldIssueAccountScopedTokenAtPickerWhenNoProfileIsSelected() {
    var account = account();

    var token = issuer.issue(accountContext(account));

    assertThat(token.scope()).isEqualTo(TokenScope.ACCOUNT);
    var decoded = buildDecoder().decode(token.value());
    assertThat(decoded.getClaimAsString(TokenClaims.SCOPE)).isEqualTo("account");
    assertThat(decoded.getClaimAsString(TokenClaims.HOUSEHOLD_ID))
        .isEqualTo(account.getHouseholdId().toString());
    assertThat(decoded.getClaimAsString(TokenClaims.CONTEXT_HOUSEHOLD_ID))
        .isEqualTo(account.getHouseholdId().toString());
    assertThat(decoded.getClaims()).doesNotContainKey("sa");
    assertThat(decoded.getClaimAsString(TokenClaims.PROFILE_ID)).isNull();
  }

  @Test
  @DisplayName("Should take the session's remembered context when building from a session")
  void shouldTakeSessionsRememberedContextWhenBuildingFromSession() {
    var account = account();
    var visited = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    var session =
        AuthSession.builder()
            .id(UUID.randomUUID())
            .accountId(account.getId())
            .contextHouseholdId(visited)
            .selectedProfileId(profileId)
            .build();

    var context = TokenContext.of(account, session);

    assertThat(context.contextHouseholdId()).isEqualTo(visited);
    assertThat(context.profileId()).contains(profileId);
    assertThat(context.scope()).isEqualTo(TokenScope.PROFILE);
    assertThat(TokenContext.of(account, session(account)).contextHouseholdId())
        .isEqualTo(account.getHouseholdId());
  }

  @Test
  @DisplayName("Should represent a missing reauthentication instant without returning null")
  void shouldRepresentMissingReauthenticationInstantWithoutReturningNull() {
    var context = TokenContext.of(account(), session(account()));

    assertThat(context.reauthenticatedAt()).isEmpty();
    assertThat(context.profileId()).isEmpty();
  }

  @Test
  @DisplayName("Should reject a null reauthentication instant")
  void shouldRejectNullReauthenticationInstant() {
    var context = TokenContext.of(account(), session(account()));

    assertThatThrownBy(() -> context.withReauthenticatedAt((Instant) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("Should use the account household when a session has no remembered context")
  void shouldUseAccountHouseholdWhenSessionHasNoRememberedContext() {
    var account = account();
    var session = AuthSession.builder().id(UUID.randomUUID()).accountId(account.getId()).build();

    assertThat(TokenContext.of(account, session).contextHouseholdId())
        .isEqualTo(account.getHouseholdId());
  }

  @Test
  @DisplayName("Should expire a reauthenticated token at the ceremony window end")
  void shouldExpireReauthenticatedTokenAtCeremonyWindowEnd() {
    var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    var fixedIssuer = issuerAt(Clock.fixed(now, ZoneOffset.UTC));

    var token =
        fixedIssuer.issueReauthenticated(
            accountContext(account()), now.plus(Duration.ofMinutes(30)));

    assertThat(token.expiresAt()).isEqualTo(now.plus(properties.reauthenticationWindow()));
    var decoded = buildDecoder().decode(token.value());
    assertThat(decoded.getExpiresAt()).isEqualTo(token.expiresAt());
    assertThat(decoded.getClaimAsInstant(TokenClaims.REAUTHENTICATED_AT)).isEqualTo(now);
  }

  @Test
  @DisplayName("Should cap a reauthenticated token when its source expires sooner")
  void shouldCapReauthenticatedTokenWhenSourceExpiresSooner() {
    var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    var fixedIssuer = issuerAt(Clock.fixed(now, ZoneOffset.UTC));
    var sourceExpiry = now.plus(Duration.ofMinutes(3));

    var token = fixedIssuer.issueReauthenticated(accountContext(account()), sourceExpiry);

    assertThat(token.expiresAt()).isEqualTo(sourceExpiry);
    assertThat(buildDecoder().decode(token.value()).getExpiresAt()).isEqualTo(sourceExpiry);
  }

  @Test
  @DisplayName("Should cap derived expiry when source expires sooner")
  void shouldCapDerivedExpiryWhenSourceExpiresSooner() {
    var now = Instant.parse("2026-07-10T12:00:00Z");
    var fixedIssuer = issuerAt(Clock.fixed(now, ZoneOffset.UTC));
    var sourceExpiry = now.plus(Duration.ofMinutes(3));

    var token = fixedIssuer.issueDerived(accountContext(account()), sourceExpiry);

    assertThat(token.expiresAt()).isEqualTo(sourceExpiry);
  }

  @Test
  @DisplayName("Should use configured ttl when source expires later")
  void shouldUseConfiguredTtlWhenSourceExpiresLater() {
    var now = Instant.parse("2026-07-10T12:00:00Z");
    var fixedIssuer = issuerAt(Clock.fixed(now, ZoneOffset.UTC));
    var sourceExpiry = now.plus(Duration.ofMinutes(30));

    var token = fixedIssuer.issueDerived(accountContext(account()), sourceExpiry);

    // A derived token never outlives its source, but a source with generous remaining
    // lifetime still yields only the configured TTL.
    assertThat(token.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(10)));
  }

  private AccessTokenIssuer issuerAt(Clock clock) {
    return new AccessTokenIssuer(
        cryptoConfig.jwtEncoder(cryptoConfig.tokenSigningKeys(properties)), properties, clock);
  }

  private static UserAccount account() {
    return AccountFixture.defaultAccountBuilder().id(UUID.randomUUID()).build();
  }

  private static AuthSession session(UserAccount account) {
    return AuthSession.builder()
        .id(UUID.randomUUID())
        .accountId(account.getId())
        .contextHouseholdId(account.getHouseholdId())
        .build();
  }

  private static TokenContext accountContext(UserAccount account) {
    return TokenContext.of(account, session(account));
  }

  private NimbusJwtDecoder buildDecoder() {
    return decoder(properties);
  }
}
