package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Every miss answers the same way to the caller while the server log records which miss it was. */
@Tag("UnitTest")
@DisplayName("Opaque Code Resolver Tests")
class OpaqueCodeResolverTest {

  private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

  private final OpaqueOneTimeCodes opaqueCodes = new OpaqueOneTimeCodes();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final OpaqueCodeResolver resolver =
      new OpaqueCodeResolver(
          opaqueCodes,
          new CredentialGuessThrottle(
              AuthThrottleProperties.builder()
                  .maxAttempts(5)
                  .window(Duration.ofMinutes(15))
                  .build(),
              clock),
          clock);
  private final ListAppender<ILoggingEvent> events = new ListAppender<>();
  private Level previousLevel;

  @BeforeEach
  void captureDebugEvents() {
    previousLevel = resolverLogger().getLevel();
    resolverLogger().setLevel(Level.DEBUG);
    events.start();
    resolverLogger().addAppender(events);
  }

  @AfterEach
  void releaseDebugEvents() {
    resolverLogger().detachAppender(events);
    resolverLogger().setLevel(previousLevel);
  }

  @Test
  @DisplayName("Should record the miss reason without the secret when the digest does not match")
  void shouldRecordMissReasonWithoutSecretWhenDigestDoesNotMatch() {
    var issued = opaqueCodes.issue();
    var invitation = pendingInvitation(issued);
    var wrongSecret = issued.publicId() + ".not-the-secret";

    assertThatThrownBy(() -> resolver.resolvePending(wrongSecret, byPublicId(invitation)))
        .isInstanceOf(InvalidOneTimeCodeException.class);

    assertThat(events.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
              assertThat(event.getFormattedMessage())
                  .contains("DIGEST_MISMATCH", issued.publicId())
                  .doesNotContain("not-the-secret");
            });
  }

  @Test
  @DisplayName("Should record the miss reason when the public id is unknown")
  void shouldRecordMissReasonWhenPublicIdIsUnknown() {
    assertThatThrownBy(() -> resolver.resolvePending("unknown.secret", _ -> Optional.empty()))
        .isInstanceOf(InvalidOneTimeCodeException.class);

    assertThat(events.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .asString()
        .contains("UNKNOWN_PUBLIC_ID", "unknown")
        .doesNotContain("secret");
  }

  @Test
  @DisplayName("Should record the miss reason when a matching code is no longer redeemable")
  void shouldRecordMissReasonWhenMatchingCodeIsNoLongerRedeemable() {
    var issued = opaqueCodes.issue();
    var invitation = pendingInvitation(issued);
    invitation.setExpiresAt(NOW.minusSeconds(1));

    assertThatThrownBy(() -> resolver.resolvePending(issued.code(), byPublicId(invitation)))
        .isInstanceOf(InvalidOneTimeCodeException.class);

    assertThat(events.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .asString()
        .contains("NOT_REDEEMABLE", issued.publicId());
  }

  @Test
  @DisplayName("Should record nothing when a redeemable code is resolved")
  void shouldRecordNothingWhenRedeemableCodeIsResolved() {
    var issued = opaqueCodes.issue();
    var invitation = pendingInvitation(issued);

    var resolved = resolver.resolvePending(issued.code(), byPublicId(invitation));

    assertThat(resolved).isSameAs(invitation);
    assertThat(events.list).isEmpty();
  }

  private static AccountInvitation pendingInvitation(OpaqueOneTimeCodes.IssuedCode issued) {
    return AccountInvitation.builder()
        .publicId(issued.publicId())
        .secretDigest(issued.digest())
        .expiresAt(NOW.plus(Duration.ofDays(7)))
        .build();
  }

  private static Function<String, Optional<AccountInvitation>> byPublicId(
      AccountInvitation invitation) {
    return publicId ->
        Optional.of(invitation).filter(candidate -> candidate.getPublicId().equals(publicId));
  }

  private static Logger resolverLogger() {
    return (Logger) LoggerFactory.getLogger(OpaqueCodeResolver.class);
  }
}
