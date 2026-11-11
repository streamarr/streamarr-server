package com.streamarr.server.repositories.auth;

import static com.streamarr.server.fixtures.PaginationFixture.firstSeek;
import static com.streamarr.server.jooq.generated.tables.ShareCode.SHARE_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ShareCode;
import com.streamarr.server.domain.auth.ShareCodeFilter;
import com.streamarr.server.domain.auth.ShareCodeStatus;
import com.streamarr.server.domain.pagination.ListOrder;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.domain.pagination.SortValueKind;
import com.streamarr.server.services.auth.ShareCodes;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.TestFixtureWork;
import com.streamarr.server.support.security.WithAccountContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Share Code Repository Integration Tests")
@WithAccountContext
class ShareCodeRepositoryIT extends AbstractIntegrationTest {

  private static final int CONCURRENT_OFFERS = 8;
  private static final ListOrder NEWEST_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.DESC).build();

  @Autowired private ShareCodeRepository shareCodeRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;
  @Autowired private ShareCodes shareCodes;

  private AuthTestSupport.TestIdentity host;
  private Instant now;

  @BeforeEach
  void setUp() {
    host = authTestSupport.createIdentity();
    now = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  @AfterEach
  void tearDown() {
    authTestSupport.deleteIdentity(host);
  }

  @Test
  @DisplayName("Should store a live code that its digest finds when a code is inserted")
  void shouldStoreLiveCodeThatItsDigestFindsWhenCodeIsInserted() {
    var issued = shareCodes.issue();

    var id = insert(issued.digest(), now.plus(7, ChronoUnit.DAYS));

    var stored = shareCodeRepository.findByCodeDigest(issued.digest()).orElseThrow();
    assertThat(stored.getId()).isEqualTo(id);
    assertThat(stored.getStatus()).isEqualTo(ShareCodeStatus.LIVE);
    assertThat(stored.getHouseholdId()).isEqualTo(host.household().getId());
    assertThat(stored.getIssuerAccountId()).isEqualTo(host.account().getId());
    assertThat(stored.getExpiresAt()).isEqualTo(now.plus(7, ChronoUnit.DAYS));
    assertThat(stored.getEndedAt()).isNull();
    assertThat(stored.getCreatedOn()).isNotNull();
  }

  @Test
  @DisplayName("Should store nothing when another code already has the digest")
  void shouldStoreNothingWhenAnotherCodeAlreadyHasDigest() {
    var issued = shareCodes.issue();
    insert(issued.digest(), now.plus(7, ChronoUnit.DAYS));

    var collided =
        transactionTemplate.execute(
            _ -> shareCodeRepository.tryInsert(unsaved(issued.digest(), now.plusSeconds(60))));

    assertThat(collided).isEmpty();
    assertThat(dsl.fetchCount(SHARE_CODE, SHARE_CODE.HOUSEHOLD_ID.eq(host.household().getId())))
        .isOne();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Ending.class)
  @DisplayName("Should end a live code exactly once when it ends")
  void shouldEndLiveCodeExactlyOnceWhenItEnds(Ending ending) {
    var id = insert(shareCodes.issue().digest(), now.plus(7, ChronoUnit.DAYS));

    var first = transactionTemplate.execute(_ -> ending.apply(shareCodeRepository, id, now));
    var again = transactionTemplate.execute(_ -> ending.apply(shareCodeRepository, id, now));
    var anyOther =
        transactionTemplate.execute(
            _ -> ending.other().apply(shareCodeRepository, id, now.plusSeconds(1)));

    var ended = shareCodeRepository.findById(id).orElseThrow();
    assertThat(first).isTrue();
    assertThat(again).isFalse();
    assertThat(anyOther).isFalse();
    assertThat(ended.getStatus()).isEqualTo(ending.status());
    assertThat(ended.getEndedAt()).isEqualTo(now);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Ending.class)
  @DisplayName("Should leave a code that read as expired when an ending arrives after its expiry")
  void shouldLeaveCodeThatReadsAsExpiredWhenEndingArrivesAfterItsExpiry(Ending ending) {
    var id = insert(shareCodes.issue().digest(), now);

    var ended = transactionTemplate.execute(_ -> ending.apply(shareCodeRepository, id, now));

    var stored = shareCodeRepository.findById(id).orElseThrow();
    assertThat(ended).isFalse();
    assertThat(stored.getStatus()).isEqualTo(ShareCodeStatus.LIVE);
    assertThat(stored.statusAt(now)).isEqualTo(ShareCodeStatus.EXPIRED);
  }

  @Test
  @DisplayName("Should invalidate only the issuer's live codes when its codes are invalidated")
  void shouldInvalidateOnlyIssuersLiveCodesWhenItsCodesAreInvalidated() {
    var other = authTestSupport.createIdentity();
    try {
      var live = insert(shareCodes.issue().digest(), now.plus(7, ChronoUnit.DAYS));
      var forOtherHousehold =
          insertFor(other.household().getId(), host.account().getId(), now.plusSeconds(60));
      var expired = insert(shareCodes.issue().digest(), now);
      var withdrawn = insert(shareCodes.issue().digest(), now.plus(7, ChronoUnit.DAYS));
      transactionTemplate.execute(_ -> shareCodeRepository.tryWithdraw(withdrawn, now));
      var someoneElses =
          insertFor(host.household().getId(), other.account().getId(), now.plusSeconds(60));

      var invalidated =
          transactionTemplate.execute(
              _ -> shareCodeRepository.invalidateLiveIssuedBy(host.account().getId(), now));

      assertThat(invalidated).isEqualTo(2);
      assertThat(statusOf(live)).isEqualTo(ShareCodeStatus.INVALIDATED);
      assertThat(shareCodeRepository.findById(live).orElseThrow().getEndedAt()).isEqualTo(now);
      assertThat(statusOf(forOtherHousehold)).isEqualTo(ShareCodeStatus.INVALIDATED);
      assertThat(statusOf(expired)).isEqualTo(ShareCodeStatus.LIVE);
      assertThat(statusOf(withdrawn)).isEqualTo(ShareCodeStatus.WITHDRAWN);
      assertThat(statusOf(someoneElses)).isEqualTo(ShareCodeStatus.LIVE);
    } finally {
      authTestSupport.deleteIdentity(other);
    }
  }

  @Test
  @DisplayName(
      "Should read each code as it stands at the filter's instant when a list keeps statuses")
  void shouldReadEachCodeAsItStandsAtFiltersInstantWhenListKeepsStatuses() {
    var live = insert(shareCodes.issue().digest(), now.plusSeconds(60));
    var expired = insert(shareCodes.issue().digest(), now);
    var spent = insert(shareCodes.issue().digest(), now.plusSeconds(60));
    transactionTemplate.execute(_ -> shareCodeRepository.trySpend(spent, now));

    assertThat(listed()).containsExactlyInAnyOrder(live, expired, spent);
    assertThat(listed(ShareCodeStatus.LIVE)).containsExactly(live);
    assertThat(listed(ShareCodeStatus.EXPIRED)).containsExactly(expired);
    assertThat(listed(ShareCodeStatus.SPENT, ShareCodeStatus.LIVE))
        .containsExactlyInAnyOrder(spent, live);
    assertThat(
            shareCodeRepository.rowsInHousehold(
                new ShareCodeFilter(UUID.randomUUID(), Set.of(), now), firstSeek(NEWEST_FIRST, 10)))
        .isEmpty();
  }

  @Test
  @DisplayName("Should spend a code once when many offers present it at the same time")
  void shouldSpendCodeOnceWhenManyOffersPresentItAtSameTime() throws Exception {
    var id = insert(shareCodes.issue().digest(), now.plus(7, ChronoUnit.DAYS));
    var start = new CountDownLatch(1);
    var spends = new ArrayList<Future<Boolean>>();

    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      for (var offer = 0; offer < CONCURRENT_OFFERS; offer++) {
        spends.add(
            executor.submit(
                () -> {
                  start.await();
                  return transactionTemplate.execute(
                      _ -> shareCodeRepository.trySpend(id, Instant.now()));
                }));
      }

      start.countDown();
    }

    var winners = 0;
    for (var spend : spends) {
      winners += Boolean.TRUE.equals(spend.get()) ? 1 : 0;
    }

    assertThat(winners).isOne();
    assertThat(shareCodeRepository.findById(id).orElseThrow().getStatus())
        .isEqualTo(ShareCodeStatus.SPENT);
  }

  @Test
  @DisplayName("Should delete the codes of a Household when the Household is deleted")
  void shouldDeleteCodesOfHouseholdWhenHouseholdIsDeleted() {
    var other = authTestSupport.createIdentity();
    var id =
        transactionTemplate.execute(
            _ ->
                shareCodeRepository
                    .tryInsert(
                        ShareCode.builder()
                            .householdId(other.household().getId())
                            .issuerAccountId(other.account().getId())
                            .codeDigest(shareCodes.issue().digest())
                            .expiresAt(now.plus(7, ChronoUnit.DAYS))
                            .build())
                    .orElseThrow());

    authTestSupport.deleteIdentity(other);

    assertThat(shareCodeRepository.findById(id)).isEmpty();
  }

  @Test
  @DisplayName("Should refuse a digest when it is not a SHA-256 digest")
  void shouldRefuseDigestWhenItIsNotSha256Digest() {
    var shortDigest = new byte[31];

    assertThatThrownBy(() -> insert(shortDigest, now.plusSeconds(60)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_share_code_code_digest_length");
  }

  @Test
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @DisplayName("Should refuse a code that leaves LIVE when it records no end")
  void shouldRefuseCodeThatLeavesLiveWhenItRecordsNoEnd() {
    var id = insert(shareCodes.issue().digest(), now.plus(7, ChronoUnit.DAYS));

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    _ ->
                        dsl.update(SHARE_CODE)
                            .set(
                                SHARE_CODE.STATUS,
                                com.streamarr.server.jooq.generated.enums.ShareCodeStatus.SPENT)
                            .where(SHARE_CODE.ID.eq(id))
                            .execute()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_share_code_ended_at");
  }

  private List<UUID> listed(ShareCodeStatus... statuses) {
    return shareCodeRepository
        .rowsInHousehold(
            new ShareCodeFilter(host.household().getId(), Set.of(statuses), now),
            firstSeek(NEWEST_FIRST, 10))
        .stream()
        .map(row -> row.item().getId())
        .toList();
  }

  private UUID insert(byte[] digest, Instant expiresAt) {
    return transactionTemplate.execute(
        _ -> shareCodeRepository.tryInsert(unsaved(digest, expiresAt)).orElseThrow());
  }

  private UUID insertFor(UUID householdId, UUID issuerAccountId, Instant expiresAt) {
    var unsaved =
        unsaved(shareCodes.issue().digest(), expiresAt).toBuilder()
            .householdId(householdId)
            .issuerAccountId(issuerAccountId)
            .build();
    return transactionTemplate.execute(_ -> shareCodeRepository.tryInsert(unsaved).orElseThrow());
  }

  private ShareCodeStatus statusOf(UUID shareCodeId) {
    return shareCodeRepository.findById(shareCodeId).orElseThrow().getStatus();
  }

  private ShareCode unsaved(byte[] digest, Instant expiresAt) {
    return ShareCode.builder()
        .householdId(host.household().getId())
        .issuerAccountId(host.account().getId())
        .codeDigest(digest)
        .expiresAt(expiresAt)
        .build();
  }

  /** The ways a live code ends, each with another that must then find it ended. */
  enum Ending {
    SPEND(ShareCodeStatus.SPENT, ShareCodeRepository::trySpend),
    WITHDRAW(ShareCodeStatus.WITHDRAWN, ShareCodeRepository::tryWithdraw),
    INVALIDATE(ShareCodeStatus.INVALIDATED, ShareCodeRepository::tryInvalidate);

    private final ShareCodeStatus status;
    private final Transition transition;

    Ending(ShareCodeStatus status, Transition transition) {
      this.status = status;
      this.transition = transition;
    }

    ShareCodeStatus status() {
      return status;
    }

    boolean apply(ShareCodeRepository repository, UUID id, Instant at) {
      return transition.apply(repository, id, at);
    }

    Ending other() {
      return values()[(ordinal() + 1) % values().length];
    }
  }

  @FunctionalInterface
  interface Transition {
    boolean apply(ShareCodeRepository repository, UUID id, Instant at);
  }
}
