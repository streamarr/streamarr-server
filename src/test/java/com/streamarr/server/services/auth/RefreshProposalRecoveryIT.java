package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.RefreshToken;
import com.streamarr.server.domain.auth.RefreshTokenStatus;
import com.streamarr.server.exceptions.InvalidRefreshProposalException;
import com.streamarr.server.exceptions.InvalidRefreshTokenException;
import com.streamarr.server.exceptions.TokenReuseDetectedException;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.RefreshProposalFixture;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.RefreshTokenRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@Tag("IntegrationTest")
@DisplayName("Refresh Proposal Recovery Integration Tests")
class RefreshProposalRecoveryIT extends AbstractIntegrationTest {

  @Autowired private RefreshTokenService refreshTokenService;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private AuthSessionRepository authSessionRepository;

  @Autowired private RefreshTokenRepository refreshTokenRepository;

  private final List<UUID> accountIds = new ArrayList<>();

  @AfterEach
  void deleteAccountsAndCascades() {
    // FK cascades sweep auth_session and refresh_token rows.
    accountIds.forEach(userAccountRepository::deleteById);
    accountIds.clear();
  }

  @Test
  @DisplayName("Should recover the same successor when the exact pair is replayed after commit")
  void shouldRecoverSameSuccessorWhenExactPairReplayedAfterCommit() {
    var issued = issueSession("lost-response-device");
    var proposal = RefreshProposalFixture.proposal();
    redeem(issued.rawToken(), proposal);

    // A separate transaction reading only committed state — the position a restarted process or
    // a second instance is in when the client retries the pair it persisted before sending.
    var recovery = redeem(issued.rawToken(), proposal);

    assertThat(recovery).isInstanceOf(RefreshResult.Recovered.class);
    assertThat(((RefreshResult.Recovered) recovery).rawRefreshToken()).isEqualTo(proposal);
    assertThat(tokensOf(issued.session().getId())).hasSize(2);
    assertThat(sessionOf(issued).getRevokedAt()).isNull();
  }

  @Test
  @DisplayName("Should converge on one successor when the identical pair is sent concurrently")
  void shouldConvergeOnOneSuccessorWhenIdenticalPairSentConcurrently() {
    var issued = issueSession("race-device");
    var proposal = RefreshProposalFixture.proposal();

    var outcomes =
        redeemConcurrently(
            List.of(command(issued.rawToken(), proposal), command(issued.rawToken(), proposal)));

    assertThat(outcomes.exceptions()).isEmpty();
    assertThat(outcomes.results()).filteredOn(RefreshResult.Rotated.class::isInstance).hasSize(1);
    assertThat(outcomes.results())
        .hasSize(2)
        .allSatisfy(result -> assertThat(rawTokenOf(result)).isEqualTo(proposal));
    assertThat(activeTokensOf(issued.session().getId())).hasSize(1);
  }

  @Test
  @DisplayName("Should revoke the loser when two different proposals race the same token")
  void shouldRevokeLoserWhenTwoDifferentProposalsRaceSameToken() {
    var issued = issueSession("conflicting-device");

    var outcomes =
        redeemConcurrently(
            List.of(
                command(issued.rawToken(), RefreshProposalFixture.proposal()),
                command(issued.rawToken(), RefreshProposalFixture.proposal())));

    // Fail closed: only one proposal can be the successor, and the other caller is
    // indistinguishable from someone replaying a stolen token.
    assertThat(outcomes.results()).singleElement().isInstanceOf(RefreshResult.Rotated.class);
    assertThat(outcomes.exceptions())
        .singleElement()
        .isInstanceOf(TokenReuseDetectedException.class);
    assertThat(sessionOf(issued).getRevokedAt()).isNotNull();
  }

  @Test
  @DisplayName("Should leave the presented token active when the proposal digest collides")
  void shouldLeavePresentedTokenActiveWhenProposalDigestCollides() {
    var first = issueSession("first-device");
    var proposal = RefreshProposalFixture.proposal();
    redeem(first.rawToken(), proposal);

    var second = issueSession("second-device");

    assertThatThrownBy(() -> redeem(second.rawToken(), proposal))
        .isInstanceOf(InvalidRefreshProposalException.class);

    // The rollback is the point: a rejected proposal must not cost the caller its session.
    assertThat(activeTokensOf(second.session().getId())).hasSize(1);
    assertThat(sessionOf(second).getRevokedAt()).isNull();
  }

  @Test
  @DisplayName("Should reject without revocation when the recovered successor has itself rotated")
  void shouldRejectWithoutRevocationWhenRecoveredSuccessorHasItselfRotated() {
    var issued = issueSession("behind-device");
    var firstProposal = RefreshProposalFixture.proposal();
    redeem(issued.rawToken(), firstProposal);
    redeem(firstProposal, RefreshProposalFixture.proposal());

    assertThatThrownBy(() -> redeem(issued.rawToken(), firstProposal))
        .isInstanceOf(InvalidRefreshTokenException.class);
    assertThat(sessionOf(issued).getRevokedAt()).isNull();
  }

  @Test
  @DisplayName("Should keep the active successor when its predecessor row is deleted")
  void shouldKeepActiveSuccessorWhenPredecessorRowDeleted() {
    var issued = issueSession("cleanup-device");
    var proposal = RefreshProposalFixture.proposal();
    redeem(issued.rawToken(), proposal);

    refreshTokenRepository.deleteById(consumedTokenOf(issued.session().getId()).getId());

    // ON DELETE SET NULL, never CASCADE: cleanup may cost recoverability, never a live credential.
    assertThat(activeTokensOf(issued.session().getId()))
        .singleElement()
        .satisfies(successor -> assertThat(successor.getPredecessorId()).isNull());
    assertThat(redeem(proposal, RefreshProposalFixture.proposal()))
        .isInstanceOf(RefreshResult.Rotated.class);
  }

  @Test
  @DisplayName("Should refuse a second successor for one predecessor")
  void shouldRefuseSecondSuccessorForOnePredecessor() {
    var issued = issueSession("lineage-device");
    redeem(issued.rawToken(), RefreshProposalFixture.proposal());

    var duplicate =
        RefreshToken.builder()
            .sessionId(issued.session().getId())
            .digest(UUID.randomUUID().toString())
            .status(RefreshTokenStatus.REVOKED)
            .expiresAt(Instant.now().plus(Duration.ofDays(1)))
            .predecessorId(consumedTokenOf(issued.session().getId()).getId())
            .build();

    assertThatThrownBy(() -> refreshTokenRepository.saveAndFlush(duplicate))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private IssuedRefreshToken issueSession(String deviceName) {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    accountIds.add(account.getId());
    return refreshTokenService.createSession(account, deviceName);
  }

  private RefreshResult redeem(String refreshToken, String proposal) {
    return refreshTokenService.redeem(command(refreshToken, proposal));
  }

  private static RefreshCommand command(String refreshToken, String proposal) {
    return RefreshCommand.builder().refreshToken(refreshToken).proposedSuccessor(proposal).build();
  }

  private static String rawTokenOf(RefreshResult result) {
    return switch (result) {
      case RefreshResult.Rotated(String successor, _) -> successor;
      case RefreshResult.Recovered(String successor, _) -> successor;
      case RefreshResult.GraceRetry(String successor, _) -> successor;
      case RefreshResult.SupersededRetry _ -> null;
    };
  }

  private ConcurrentOutcomes redeemConcurrently(List<RefreshCommand> commands) {
    var executor = Executors.newFixedThreadPool(commands.size());
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(commands.size());
    var results = new CopyOnWriteArrayList<RefreshResult>();
    var exceptions = new CopyOnWriteArrayList<Exception>();

    commands.forEach(
        command ->
            executor.submit(
                () -> {
                  try {
                    startLatch.await();
                    results.add(refreshTokenService.redeem(command));
                  } catch (Exception e) {
                    exceptions.add(e);
                  } finally {
                    doneLatch.countDown();
                  }
                }));

    startLatch.countDown();
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(doneLatch.getCount()).isZero());
    executor.shutdown();

    return new ConcurrentOutcomes(results, exceptions);
  }

  private AuthSession sessionOf(IssuedRefreshToken issued) {
    return authSessionRepository.findById(issued.session().getId()).orElseThrow();
  }

  private RefreshToken consumedTokenOf(UUID sessionId) {
    return tokensOf(sessionId).stream()
        .filter(token -> token.getStatus() == RefreshTokenStatus.ROTATED)
        .findFirst()
        .orElseThrow();
  }

  private List<RefreshToken> tokensOf(UUID sessionId) {
    return refreshTokenRepository.findAll().stream()
        .filter(token -> sessionId.equals(token.getSessionId()))
        .toList();
  }

  private List<RefreshToken> activeTokensOf(UUID sessionId) {
    return tokensOf(sessionId).stream()
        .filter(token -> token.getStatus() == RefreshTokenStatus.ACTIVE)
        .toList();
  }

  private record ConcurrentOutcomes(List<RefreshResult> results, List<Exception> exceptions) {}
}
