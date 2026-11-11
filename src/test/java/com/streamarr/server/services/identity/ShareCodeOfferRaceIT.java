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
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ShareCodeRepository;
import com.streamarr.server.services.auth.ShareCodes;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import com.streamarr.server.support.TestFixtureWork;
import java.time.Duration;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A share code admits one offer: the offer spends it in the transaction that creates the offer, so
 * of two offers that present one code, exactly one is made (ADR 0024 §Share codes). The race is
 * held at the code's row: both offers present the code, wait to spend it, and are released
 * together.
 */
@Tag("IntegrationTest")
@DisplayName("Share Code Offer Race Integration Tests")
class ShareCodeOfferRaceIT extends AbstractIntegrationTest {

  private static final String WAITING_TO_SPEND = "update%share_code%";

  @Autowired private ProfileSharingService profileSharingService;
  @Autowired private ShareCodeRepository shareCodeRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ShareCodes shareCodes;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();
  private AuthTestSupport.TestIdentity host;

  @BeforeEach
  void setUp() {
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
    var codeRow =
        RowLockTarget.builder()
            .dataSource(dataSource)
            .table("share_code")
            .rowId(stored(code).getId())
            .build();

    List<Outcome<ProfileHouseholdShare, ShareRejections.Offer>> outcomes;
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var codeLock = lockRow(codeRow)) {
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

  private Outcome<ProfileHouseholdShare, ShareRejections.Offer> offerOwnProfile(
      AuthTestSupport.TestIdentity offerer, String code) {
    return profileSharingService.offerProfileShare(
        authTestSupport.identityOf(offerer),
        offerer.profile().getId(),
        ShareCodeCommand.builder().code(code).ipAddress("203.0.113.7").build());
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
