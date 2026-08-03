package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.domain.auth.RefreshToken;
import com.streamarr.server.domain.auth.RefreshTokenStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.exceptions.InvalidRefreshProposalException;
import com.streamarr.server.exceptions.InvalidRefreshTokenException;
import com.streamarr.server.exceptions.TokenReuseDetectedException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeRefreshTokenRepository;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.RefreshProposalFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Proposed Successor Refresh Tests")
class ProposedSuccessorRefreshTest {

  private static final Duration ROTATION_GRACE = Duration.ofSeconds(30);
  private static final Duration PAST_GRACE = Duration.ofMinutes(10);

  private final AtomicReference<Instant> currentTime =
      new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

  private final FakeAuthSessionRepository sessionRepository = new FakeAuthSessionRepository();
  private final FakeRefreshTokenRepository tokenRepository = new FakeRefreshTokenRepository();

  private final AuthTokenProperties properties =
      AuthTokenProperties.builder()
          .signingKey("")
          .accessTokenTtl(Duration.ofMinutes(10))
          .refreshTokenTtl(Duration.ofDays(30))
          .rotationGrace(ROTATION_GRACE)
          .build();

  private final MutableClock clock = new MutableClock(currentTime);

  private final TokenReuseRevoker tokenReuseRevoker =
      new TokenReuseRevoker(new TokenReuseRevocationWriter(sessionRepository, tokenRepository));

  private final RefreshTokenService service =
      new RefreshTokenService(
          sessionRepository, tokenRepository, properties, clock, tokenReuseRevoker);

  @Test
  @DisplayName("Should issue the client's proposal when rotating a bearer token")
  void shouldIssueClientProposalWhenRotatingBearerToken() {
    var issued = issueSession();
    var proposal = RefreshProposalFixture.proposal();

    var result = redeem(issued.rawToken(), proposal);

    assertThat(result).isInstanceOf(RefreshResult.Rotated.class);
    assertThat(((RefreshResult.Rotated) result).rawRefreshToken()).isEqualTo(proposal);
    assertThat(activeTokens()).hasSize(1);
  }

  @Test
  @DisplayName("Should record lineage on the successor when rotating to a proposal")
  void shouldRecordLineageOnSuccessorWhenRotatingToProposal() {
    var issued = issueSession();
    var predecessorId = tokenRepository.findAll().getFirst().getId();

    redeem(issued.rawToken(), RefreshProposalFixture.proposal());

    assertThat(activeTokens())
        .singleElement()
        .satisfies(successor -> assertThat(successor.getPredecessorId()).isEqualTo(predecessorId));
  }

  @Test
  @DisplayName("Should return the same successor when the exact pair is replayed past grace")
  void shouldReturnSameSuccessorWhenExactPairReplayedPastGrace() {
    var issued = issueSession();
    var proposal = RefreshProposalFixture.proposal();
    redeem(issued.rawToken(), proposal);

    advanceClock(PAST_GRACE);
    var recovery = redeem(issued.rawToken(), proposal);

    assertThat(recovery).isInstanceOf(RefreshResult.Recovered.class);
    assertThat(((RefreshResult.Recovered) recovery).rawRefreshToken()).isEqualTo(proposal);
    assertThat(sessionRepository.findById(issued.session().getId()).orElseThrow().getRevokedAt())
        .isNull();
  }

  @Test
  @DisplayName("Should not rotate a second time when recovering the exact pair")
  void shouldNotRotateSecondTimeWhenRecoveringExactPair() {
    var issued = issueSession();
    var proposal = RefreshProposalFixture.proposal();
    redeem(issued.rawToken(), proposal);

    advanceClock(PAST_GRACE);
    redeem(issued.rawToken(), proposal);
    redeem(issued.rawToken(), proposal);

    assertThat(tokenRepository.findAll()).hasSize(2);
    assertThat(activeTokens()).hasSize(1);
  }

  @Test
  @DisplayName("Should echo the proposal rather than a derived successor when replayed in grace")
  void shouldEchoProposalRatherThanDerivedSuccessorWhenReplayedInGrace() {
    var issued = issueSession();
    var proposal = RefreshProposalFixture.proposal();
    redeem(issued.rawToken(), proposal);

    advanceClock(Duration.ofSeconds(5));
    var recovery = redeem(issued.rawToken(), proposal);

    // The pre-proposal grace path derives its own successor; a bearer client that received one
    // would treat the mismatched echo as terminal and re-pair a perfectly recoverable session.
    assertThat(recovery).isInstanceOf(RefreshResult.Recovered.class);
    assertThat(((RefreshResult.Recovered) recovery).rawRefreshToken()).isEqualTo(proposal);
  }

  @Test
  @DisplayName("Should revoke the family when a consumed token carries a different proposal")
  void shouldRevokeFamilyWhenConsumedTokenCarriesDifferentProposal() {
    var issued = issueSession();
    redeem(issued.rawToken(), RefreshProposalFixture.proposal());

    advanceClock(PAST_GRACE);

    assertThatThrownBy(() -> redeem(issued.rawToken(), RefreshProposalFixture.proposal()))
        .isInstanceOf(TokenReuseDetectedException.class);
    assertThat(sessionRepository.findById(issued.session().getId()).orElseThrow())
        .satisfies(
            session -> {
              assertThat(session.getRevokedAt()).isNotNull();
              assertThat(session.getRevokedReason()).isEqualTo(SessionRevocationReason.TOKEN_REUSE);
            });
  }

  @Test
  @DisplayName("Should revoke the family when a different proposal arrives inside grace")
  void shouldRevokeFamilyWhenDifferentProposalArrivesInsideGrace() {
    var issued = issueSession();
    redeem(issued.rawToken(), RefreshProposalFixture.proposal());

    advanceClock(Duration.ofSeconds(5));

    assertThatThrownBy(() -> redeem(issued.rawToken(), RefreshProposalFixture.proposal()))
        .isInstanceOf(TokenReuseDetectedException.class);
    assertThat(sessionRepository.findById(issued.session().getId()).orElseThrow().getRevokedAt())
        .isNotNull();
  }

  @Test
  @DisplayName("Should reject without revocation when the exact pair's successor has rotated")
  void shouldRejectWithoutRevocationWhenExactPairSuccessorHasRotated() {
    var issued = issueSession();
    var firstProposal = RefreshProposalFixture.proposal();
    redeem(issued.rawToken(), firstProposal);
    redeem(firstProposal, RefreshProposalFixture.proposal());

    advanceClock(PAST_GRACE);

    assertThatThrownBy(() -> redeem(issued.rawToken(), firstProposal))
        .isInstanceOf(InvalidRefreshTokenException.class);
    assertThat(sessionRepository.findById(issued.session().getId()).orElseThrow().getRevokedAt())
        .isNull();
    assertThat(activeTokens()).hasSize(1);
  }

  @Test
  @DisplayName("Should reject without revocation when the exact pair's lineage was cleaned up")
  void shouldRejectWithoutRevocationWhenExactPairLineageCleanedUp() {
    var issued = issueSession();
    var proposal = RefreshProposalFixture.proposal();
    redeem(issued.rawToken(), proposal);
    var predecessor = tokenRepository.findAll().stream().filter(this::isConsumed).findFirst();
    tokenRepository.delete(predecessor.orElseThrow());

    advanceClock(PAST_GRACE);

    assertThatThrownBy(() -> redeem(issued.rawToken(), proposal))
        .isInstanceOf(InvalidRefreshTokenException.class);
    assertThat(sessionRepository.findById(issued.session().getId()).orElseThrow().getRevokedAt())
        .isNull();
  }

  @Test
  @DisplayName("Should never return a recovered successor after a later rotation wins")
  void shouldNeverReturnRecoveredSuccessorAfterLaterRotationWins() {
    var issued = issueSession();
    var firstProposal = RefreshProposalFixture.proposal();
    redeem(issued.rawToken(), firstProposal);

    var secondProposal = RefreshProposalFixture.proposal();
    redeem(firstProposal, secondProposal);

    assertThatThrownBy(() -> redeem(issued.rawToken(), firstProposal))
        .isInstanceOf(InvalidRefreshTokenException.class);
    assertThat(activeTokens())
        .singleElement()
        .satisfies(token -> assertThat(token.getDigest()).isNotEqualTo(firstProposal));
  }

  @Test
  @DisplayName("Should revoke the family when a consumed token proposes an unrelated successor")
  void shouldRevokeFamilyWhenConsumedTokenProposesUnrelatedSuccessor() {
    var issued = issueSession();
    var proposal = RefreshProposalFixture.proposal();
    redeem(issued.rawToken(), proposal);
    var stolen = issued.rawToken();
    var attackerProposal = RefreshProposalFixture.proposal();

    assertThatThrownBy(() -> redeem(stolen, attackerProposal))
        .isInstanceOf(TokenReuseDetectedException.class);
    assertThat(tokenRepository.findAll())
        .allSatisfy(token -> assertThat(token.getStatus()).isEqualTo(RefreshTokenStatus.REVOKED));
  }

  @ParameterizedTest(name = "Should reject malformed proposal \"{0}\"")
  @ValueSource(
      strings = {
        "",
        "too-short",
        "not-base64url!!!!!!!!!!!!!!!!!!!!!!!!!!!!!",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB"
      })
  @DisplayName("Should reject a proposal that is not a canonical 256-bit token")
  void shouldRejectProposalThatIsNotCanonicalToken(String proposal) {
    assertThatThrownBy(
            () ->
                RefreshCommand.builder()
                    .refreshToken(RefreshProposalFixture.proposal())
                    .proposedSuccessor(proposal)
                    .build())
        .isInstanceOf(InvalidRefreshProposalException.class);
  }

  @Test
  @DisplayName("Should reject a proposal identical to the token it would replace")
  void shouldRejectProposalIdenticalToTokenItWouldReplace() {
    var token = RefreshProposalFixture.proposal();

    assertThatThrownBy(
            () -> RefreshCommand.builder().refreshToken(token).proposedSuccessor(token).build())
        .isInstanceOf(InvalidRefreshProposalException.class);
  }

  @Test
  @DisplayName("Should reject as a proposal error when the proposed digest already exists")
  void shouldRejectAsProposalErrorWhenProposedDigestAlreadyExists() {
    var issued = issueSession();
    var proposal = RefreshProposalFixture.proposal();
    redeem(issued.rawToken(), proposal);

    var second = issueSession();

    // A digest collision must never reach the caller as reuse: the presented token was good, and
    // the transactional rollback that keeps it ACTIVE is proven in RefreshProposalRecoveryIT.
    assertThatThrownBy(() -> redeem(second.rawToken(), proposal))
        .isInstanceOf(InvalidRefreshProposalException.class);
    assertThat(sessionRepository.findById(second.session().getId()).orElseThrow().getRevokedAt())
        .isNull();
  }

  @Test
  @DisplayName("Should reject an expired token carrying a proposal without revoking the family")
  void shouldRejectExpiredTokenCarryingProposalWithoutRevokingFamily() {
    var issued = issueSession();

    advanceClock(Duration.ofDays(31));

    assertThatThrownBy(() -> redeem(issued.rawToken(), RefreshProposalFixture.proposal()))
        .isInstanceOf(InvalidRefreshTokenException.class);
    assertThat(sessionRepository.findById(issued.session().getId()).orElseThrow().getRevokedAt())
        .isNull();
  }

  private RefreshResult redeem(String refreshToken, String proposal) {
    return service.redeem(
        RefreshCommand.builder().refreshToken(refreshToken).proposedSuccessor(proposal).build());
  }

  private IssuedRefreshToken issueSession() {
    var account = AccountFixture.defaultAccountBuilder().id(UUID.randomUUID()).build();
    return service.createSession(account, "test-device");
  }

  private List<RefreshToken> activeTokens() {
    return tokenRepository.findAll().stream()
        .filter(token -> token.getStatus() == RefreshTokenStatus.ACTIVE)
        .toList();
  }

  private boolean isConsumed(RefreshToken token) {
    return token.getStatus() == RefreshTokenStatus.ROTATED;
  }

  private void advanceClock(Duration duration) {
    currentTime.updateAndGet(instant -> instant.plus(duration));
  }
}
