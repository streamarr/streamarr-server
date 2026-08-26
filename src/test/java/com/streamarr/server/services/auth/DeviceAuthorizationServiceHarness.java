package com.streamarr.server.services.auth;

import com.streamarr.server.config.CanonicalBaseUrl;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.DeviceAuthProperties;
import com.streamarr.server.config.security.TokenCryptoConfig;
import com.streamarr.server.fakes.FakeCredentialAttemptRepository;
import com.streamarr.server.fakes.FakeServerBootstrapRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import com.streamarr.server.repositories.auth.DeviceRegistrationRepository;
import com.streamarr.server.repositories.auth.EsnBlockRepository;
import com.streamarr.server.repositories.auth.RefreshTokenRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import lombok.Builder;

/**
 * Builds a fully wired {@link DeviceAuthorizationService} over caller-supplied fakes for tests
 * outside this package — the code generators are deliberately package-private.
 */
public final class DeviceAuthorizationServiceHarness {

  private static final String TEST_KEY_BASE64 =
      "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQga+ZKCbAcyZIb7k2FE8rMPFtIpTdzX2dR/csZ8k6A95uhRANCAAQawOmVKMDLAOsboxKLb9khGsWyxwcIikucXDCfX18ME5X9/kqSS2vdMnFfZ6KR12U/Sy/EwOwnc82xFAyFdNbe";

  private DeviceAuthorizationServiceHarness() {}

  /** Pairing presumes a set-up server; the guard has its own dedicated test. */
  private static FakeServerBootstrapRepository claimedBootstrap() {
    var bootstrap = new FakeServerBootstrapRepository();
    bootstrap.claim(UUID.randomUUID());
    return bootstrap;
  }

  @Builder(builderMethodName = "harness")
  private static DeviceAuthorizationService build(
      DeviceAuthorizationRepository authorizations,
      UserAccountRepository accounts,
      DeviceRegistrationRepository registrations,
      EsnBlockRepository esnBlocks,
      AuthSessionRepository sessions,
      RefreshTokenRepository tokens,
      Clock clock) {
    var properties =
        DeviceAuthProperties.builder()
            .codeTtl(Duration.ofMinutes(10))
            .pollIntervalSeconds(5)
            .verificationPath("/link")
            .maxOutstandingCodes(3)
            .sweepInterval(Duration.ofMinutes(15))
            .build();
    var tokenProperties =
        AuthTokenProperties.builder()
            .signingKey(TEST_KEY_BASE64)
            .accessTokenTtl(Duration.ofMinutes(10))
            .refreshTokenTtl(Duration.ofDays(30))
            .rotationGrace(Duration.ofSeconds(30))
            .build();
    var cryptoConfig = new TokenCryptoConfig();
    return new DeviceAuthorizationService(
        authorizations,
        accounts,
        registrations,
        esnBlocks,
        claimedBootstrap(),
        new DeviceRegistrationLifecycle(registrations, sessions),
        new RefreshTokenService(
            sessions,
            tokens,
            tokenProperties,
            clock,
            new TokenReuseRevoker(new TokenReuseRevocationWriter(sessions, tokens))),
        new AccessTokenIssuer(
            cryptoConfig.jwtEncoder(cryptoConfig.tokenSigningKeys(tokenProperties)),
            tokenProperties,
            clock),
        new UserCodeGenerator(),
        new DeviceCodeGenerator(),
        new FakeCredentialAttemptRepository().gate(clock),
        properties,
        CanonicalBaseUrl.of("https://streamarr.example", false),
        clock);
  }
}
