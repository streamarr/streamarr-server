package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.ShareCode;
import com.streamarr.server.domain.auth.ShareCodeStatus;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeShareCodeRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.services.auth.ShareCodes;
import com.streamarr.server.services.auth.ShareCodesFixture;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The rule that a live share code lasts only while its issuer may still issue one for it. */
@Tag("UnitTest")
@DisplayName("Share Code Issuer Authority Tests")
class ShareCodeIssuerAuthorityTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final UUID ISSUER_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
  private static final UUID KEPT_HOUSEHOLD =
      UUID.fromString("50000000-0000-0000-0000-000000000001");
  private static final UUID LOST_HOUSEHOLD =
      UUID.fromString("50000000-0000-0000-0000-000000000002");

  private final FakeShareCodeRepository shareCodes = new FakeShareCodeRepository();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build())
          .decideUnitWith(
              intent ->
                  intent.equals(new Intent.IssueShareCode(KEPT_HOUSEHOLD))
                      ? new Decision.Allowed<>(AuthorizationUnit.INSTANCE)
                      : new Decision.Denied<>(Decision.DenialReason.POLICY));
  private final ShareCodeIssuerAuthority issuerAuthority =
      new ShareCodeIssuerAuthority(shareCodes, new FakeUserAccountRepository(), authorization);
  private final ShareCodes codes = ShareCodesFixture.shareCodes();

  @Test
  @DisplayName(
      "Should invalidate only the codes for Households the issuer may no longer issue for when the"
          + " issuer changes")
  void shouldInvalidateOnlyCodesForHouseholdsIssuerMayNoLongerIssueForWhenIssuerChanges() {
    var kept = liveCode(ISSUER_ID, KEPT_HOUSEHOLD);
    var lost = liveCode(ISSUER_ID, LOST_HOUSEHOLD);

    var invalidated = issuerAuthority.afterIssuerChanged(ISSUER_ID, NOW);

    assertThat(invalidated).isOne();
    assertThat(lost.getStatus()).isEqualTo(ShareCodeStatus.INVALIDATED);
    assertThat(lost.getEndedAt()).isEqualTo(NOW);
    assertThat(kept.getStatus()).isEqualTo(ShareCodeStatus.LIVE);
  }

  @Test
  @DisplayName("Should ask once for each Household when the issuer changes")
  void shouldAskOnceForEachHouseholdWhenIssuerChanges() {
    var kept = List.of(liveCode(ISSUER_ID, KEPT_HOUSEHOLD), liveCode(ISSUER_ID, KEPT_HOUSEHOLD));
    var lost =
        List.of(
            liveCode(ISSUER_ID, LOST_HOUSEHOLD),
            liveCode(ISSUER_ID, LOST_HOUSEHOLD),
            liveCode(ISSUER_ID, LOST_HOUSEHOLD));

    var invalidated = issuerAuthority.afterIssuerChanged(ISSUER_ID, NOW);

    assertThat(invalidated).isEqualTo(3);
    assertThat(lost).extracting(ShareCode::getStatus).containsOnly(ShareCodeStatus.INVALIDATED);
    assertThat(kept).extracting(ShareCode::getStatus).containsOnly(ShareCodeStatus.LIVE);
    assertThat(authorization.recordedIntents())
        .containsExactlyInAnyOrder(
            new Intent.IssueShareCode(KEPT_HOUSEHOLD), new Intent.IssueShareCode(LOST_HOUSEHOLD));
  }

  @Test
  @DisplayName(
      "Should leave codes that no longer work and other issuers' codes when the issuer changes")
  void shouldLeaveCodesThatNoLongerWorkAndOtherIssuersCodesWhenIssuerChanges() {
    var expired = liveCode(ISSUER_ID, LOST_HOUSEHOLD);
    expired.setExpiresAt(NOW);
    var withdrawn = liveCode(ISSUER_ID, LOST_HOUSEHOLD);
    shareCodes.tryWithdraw(withdrawn.getId(), NOW.minusSeconds(1));
    var someoneElses = liveCode(UUID.randomUUID(), LOST_HOUSEHOLD);

    var invalidated = issuerAuthority.afterIssuerChanged(ISSUER_ID, NOW);

    assertThat(invalidated).isZero();
    assertThat(expired.getStatus()).isEqualTo(ShareCodeStatus.LIVE);
    assertThat(expired.statusAt(NOW)).isEqualTo(ShareCodeStatus.EXPIRED);
    assertThat(withdrawn.getStatus()).isEqualTo(ShareCodeStatus.WITHDRAWN);
    assertThat(someoneElses.getStatus()).isEqualTo(ShareCodeStatus.LIVE);
    assertThat(authorization.recordedIntents()).isEmpty();
  }

  @Test
  @DisplayName("Should invalidate every live code when its issuer is about to be deleted")
  void shouldInvalidateEveryLiveCodeWhenItsIssuerIsAboutToBeDeleted() {
    var kept = liveCode(ISSUER_ID, KEPT_HOUSEHOLD);
    var lost = liveCode(ISSUER_ID, LOST_HOUSEHOLD);
    authorization.failWith(Decision.FailureCause.EVALUATION_ERROR);

    var invalidated = issuerAuthority.beforeIssuerDeleted(ISSUER_ID, NOW);

    assertThat(invalidated).isEqualTo(2);
    assertThat(kept.getStatus()).isEqualTo(ShareCodeStatus.INVALIDATED);
    assertThat(lost.getStatus()).isEqualTo(ShareCodeStatus.INVALIDATED);
    assertThat(authorization.recordedIntents()).isEmpty();
  }

  @Test
  @DisplayName("Should invalidate every live code without a decision when the issuer is disabled")
  void shouldInvalidateEveryLiveCodeWithoutDecisionWhenIssuerIsDisabled() {
    var kept = liveCode(ISSUER_ID, KEPT_HOUSEHOLD);
    var lost = liveCode(ISSUER_ID, LOST_HOUSEHOLD);
    var expired = liveCode(ISSUER_ID, LOST_HOUSEHOLD);
    expired.setExpiresAt(NOW);
    var someoneElses = liveCode(UUID.randomUUID(), LOST_HOUSEHOLD);
    authorization.failWith(Decision.FailureCause.EVALUATION_ERROR);

    var invalidated = issuerAuthority.afterIssuerDisabled(ISSUER_ID, NOW);

    assertThat(invalidated).isEqualTo(2);
    assertThat(kept.getStatus()).isEqualTo(ShareCodeStatus.INVALIDATED);
    assertThat(lost.getStatus()).isEqualTo(ShareCodeStatus.INVALIDATED);
    assertThat(lost.getEndedAt()).isEqualTo(NOW);
    assertThat(expired.statusAt(NOW)).isEqualTo(ShareCodeStatus.EXPIRED);
    assertThat(someoneElses.getStatus()).isEqualTo(ShareCodeStatus.LIVE);
    assertThat(authorization.recordedIntents()).isEmpty();
  }

  @Test
  @DisplayName("Should fail closed and invalidate nothing when authorization is unavailable")
  void shouldFailClosedAndInvalidateNothingWhenAuthorizationIsUnavailable() {
    var code = liveCode(ISSUER_ID, KEPT_HOUSEHOLD);
    authorization.failWith(Decision.FailureCause.EVALUATION_ERROR);

    assertThatThrownBy(() -> issuerAuthority.afterIssuerChanged(ISSUER_ID, NOW))
        .isInstanceOf(AuthorizationUnavailableException.class);
    assertThat(code.getStatus()).isEqualTo(ShareCodeStatus.LIVE);
  }

  private ShareCode liveCode(UUID issuerAccountId, UUID householdId) {
    var id =
        shareCodes
            .tryInsert(
                ShareCode.builder()
                    .householdId(householdId)
                    .issuerAccountId(issuerAccountId)
                    .codeDigest(codes.issue().digest())
                    .expiresAt(NOW.plusSeconds(3600))
                    .build())
            .orElseThrow();
    return shareCodes.findById(id).orElseThrow();
  }
}
