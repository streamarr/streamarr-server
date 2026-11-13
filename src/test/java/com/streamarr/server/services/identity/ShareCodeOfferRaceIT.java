package com.streamarr.server.services.identity;

import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.ShareCode;
import com.streamarr.server.domain.auth.ShareCodeStatus;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ShareCodeRepository;
import com.streamarr.server.services.auth.ShareCodes;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.ControlledClockConfiguration;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import com.streamarr.server.support.TestFixtureWork;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * A share code admits one offer while it is live: the offer spends it in the transaction that
 * creates the offer, so of two offers that present one code, exactly one is made, and an offer that
 * waits for the code until it expires is refused (ADR 0024 §Share codes). Each race is held at the
 * code's row: the offers present the code, wait to spend it, and are released together.
 */
@Tag("IntegrationTest")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Share Code Offer Race Integration Tests")
@Import(ControlledClockConfiguration.class)
class ShareCodeOfferRaceIT extends AbstractIntegrationTest {

  private static final String WAITING_TO_SPEND = "%share_code%";

  @Autowired private ProfileSharingService profileSharingService;
  @Autowired private ShareCodeRepository shareCodeRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ShareCodes shareCodes;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MutableClock clock;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();
  private AuthTestSupport.TestIdentity host;

  @BeforeEach
  void setUp() {
    clock.advance(Duration.between(clock.instant(), Instant.now()));
    host = track(authTestSupport.createIdentity());
  }

  @AfterEach
  void tearDown() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName("Should make exactly one offer when two offers present one code at the same time")
  void shouldMakeExactlyOneOfferWhenTwoOffersPresentOneCodeAtTheSameTime() throws Exception {
    var first = track(authTestSupport.createIdentity());
    var second = track(authTestSupport.createIdentity());
    var code = authTestSupport.shareCodeFor(host);

    List<Outcome<ProfileHouseholdShare, ShareRejections.Offer>> outcomes;
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var codeLock = lockRow(codeRow(code))) {
      var firstOffer = executor.submit(() -> offerOwnProfile(first, code));
      var secondOffer = executor.submit(() -> offerOwnProfile(second, code));
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> waitersBehind(jdbcTemplate, codeLock.backendPid(), WAITING_TO_SPEND) == 2);

      codeLock.release();
      outcomes =
          List.of(firstOffer.get(10, TimeUnit.SECONDS), secondOffer.get(10, TimeUnit.SECONDS));
    }

    assertThat(outcomes).filteredOn(Outcome.Accepted.class::isInstance).hasSize(1);
    assertThat(outcomes)
        .filteredOn(Outcome.Rejected.class::isInstance)
        .containsExactly(Outcome.rejected(new ShareCodeRejections.ShareCodeRefused()));
    assertThat(shareRepository.findAll())
        .filteredOn(share -> share.getHouseholdId().equals(host.household().getId()))
        .filteredOn(share -> share.getStatus() == ProfileShareStatus.PENDING)
        .singleElement()
        .extracting(ProfileHouseholdShare::getProfileId)
        .isIn(first.profile().getId(), second.profile().getId());
    assertThat(stored(code).getStatus()).isEqualTo(ShareCodeStatus.SPENT);
  }

  @Test
  @DisplayName("Should refuse an offer when its share code expires while the offer waits for it")
  void shouldRefuseOfferWhenItsShareCodeExpiresWhileTheOfferWaitsForIt() throws Exception {
    var offerer = track(authTestSupport.createIdentity());
    var code = authTestSupport.shareCodeFor(host);
    var expiresAt = stored(code).getExpiresAt();

    Outcome<ProfileHouseholdShare, ShareRejections.Offer> outcome;
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var codeLock = lockRow(codeRow(code))) {
      var offer = executor.submit(() -> offerOwnProfile(offerer, code));
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> waitersBehind(jdbcTemplate, codeLock.backendPid(), WAITING_TO_SPEND) == 1);

      clock.advance(Duration.between(clock.instant(), expiresAt).plusSeconds(1));
      codeLock.release();
      outcome = offer.get(10, TimeUnit.SECONDS);
    }

    assertThat(outcome).isEqualTo(Outcome.rejected(new ShareCodeRejections.ShareCodeRefused()));
    assertThat(shareRepository.findAll())
        .filteredOn(share -> share.getProfileId().equals(offerer.profile().getId()))
        .extracting(ProfileHouseholdShare::getHouseholdId)
        .doesNotContain(host.household().getId());
    assertThat(stored(code).statusAt(clock.instant())).isEqualTo(ShareCodeStatus.EXPIRED);
  }

  private Outcome<ProfileHouseholdShare, ShareRejections.Offer> offerOwnProfile(
      AuthTestSupport.TestIdentity offerer, String code) {
    return profileSharingService.offerProfileShare(
        authTestSupport.identityOf(offerer),
        offerer.profile().getId(),
        ShareCodeCommand.builder().code(code).ipAddress("203.0.113.7").build());
  }

  private RowLockTarget codeRow(String code) {
    return RowLockTarget.builder()
        .dataSource(dataSource)
        .table("share_code")
        .rowId(stored(code).getId())
        .build();
  }

  private ShareCode stored(String code) {
    return shareCodeRepository
        .findByCodeDigest(shareCodes.digestOf(code).orElseThrow())
        .orElseThrow();
  }

  private AuthTestSupport.TestIdentity track(AuthTestSupport.TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
