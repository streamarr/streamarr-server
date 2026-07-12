package com.streamarr.server.repositories.streaming.trust;

import static com.streamarr.server.jooq.generated.tables.TranscodeCaSigningLease.TRANSCODE_CA_SIGNING_LEASE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.jooq.generated.enums.TranscodeCaSigningOperation;
import com.streamarr.server.services.streaming.trust.CertificateAuthorityOperation;
import com.streamarr.server.services.streaming.trust.CertificateSigningLease;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Certificate Authority Signing Lease Repository Integration Tests")
class CertificateAuthoritySigningLeaseRepositoryIT extends AbstractIntegrationTest {

  private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

  @Autowired private CertificateAuthoritySigningLeaseRepository repository;
  @Autowired private DSLContext dsl;

  @BeforeEach
  void resetLease() {
    dsl.update(TRANSCODE_CA_SIGNING_LEASE)
        .set(TRANSCODE_CA_SIGNING_LEASE.OPERATION, (TranscodeCaSigningOperation) null)
        .set(TRANSCODE_CA_SIGNING_LEASE.OWNER_ID, (UUID) null)
        .set(TRANSCODE_CA_SIGNING_LEASE.LEASE_UNTIL, (OffsetDateTime) null)
        .set(TRANSCODE_CA_SIGNING_LEASE.FENCING_EPOCH, 0L)
        .execute();
  }

  @Test
  @DisplayName("Should grant exactly one signing lease when owners acquire concurrently")
  void shouldGrantExactlyOneSigningLeaseWhenOwnersAcquireConcurrently() {
    var start = new CountDownLatch(1);
    var results = new CopyOnWriteArrayList<Boolean>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var ownerId : List.of(UUID.randomUUID(), UUID.randomUUID())) {
        executor.submit(
            () -> {
              start.await();
              results.add(
                  repository
                      .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, ownerId, LEASE_DURATION)
                      .isPresent());
              return null;
            });
      }
      start.countDown();
    }

    assertThat(results).containsExactlyInAnyOrder(true, false);
  }

  @Test
  @DisplayName("Should report the exact database acquisition time")
  void shouldReportExactDatabaseAcquisitionTime() {
    var duration = Duration.ofSeconds(30).plusNanos(123_000);
    var before = databaseTime().toInstant();

    var lease =
        repository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), duration)
            .orElseThrow();

    var after = databaseTime().toInstant();
    assertThat(lease.databaseTime()).isBetween(before, after);
    assertThat(lease.databaseTime().getNano() % 1_000).isZero();
  }

  @Test
  @DisplayName("Should reject a signing lease duration below database precision")
  void shouldRejectSigningLeaseDurationBelowDatabasePrecision() {
    var ownerId = UUID.randomUUID();
    var duration = Duration.ofNanos(1);

    assertThatThrownBy(
            () -> repository.tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, ownerId, duration))
        .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("microsecond");
  }

  @Test
  @DisplayName("Should reject non-positive signing lease durations")
  void shouldRejectNonPositiveSigningLeaseDurations() {
    var current =
        repository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();
    for (var duration : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
      var ownerId = UUID.randomUUID();

      assertThatThrownBy(
              () ->
                  repository.tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, ownerId, duration))
          .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
          .hasCauseInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("positive");
      assertThatThrownBy(() -> repository.renew(current, duration))
          .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
          .hasCauseInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("positive");
    }
  }

  @Test
  @DisplayName("Should reject signing leases longer than five minutes")
  void shouldRejectSigningLeasesLongerThanFiveMinutes() {
    var ownerId = UUID.randomUUID();
    var excessiveDuration = Duration.ofMinutes(5).plusNanos(1_000);

    assertThatThrownBy(
            () ->
                repository.tryAcquire(
                    CertificateAuthorityOperation.BOOTSTRAP, ownerId, excessiveDuration))
        .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("5 minutes");

    var current =
        repository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();
    assertThatThrownBy(() -> repository.renew(current, excessiveDuration))
        .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("5 minutes");
    assertThat(repository.isCurrent(current)).isTrue();
  }

  @Test
  @DisplayName("Should retain the fence when the current owner renews before expiry")
  void shouldRetainFenceWhenCurrentOwnerRenewsBeforeExpiry() {
    var lease =
        repository
            .tryAcquire(CertificateAuthorityOperation.ISSUANCE, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();

    var renewed = repository.renew(lease, LEASE_DURATION).orElseThrow();

    assertThat(renewed.fencingEpoch()).isEqualTo(lease.fencingEpoch());
    assertThat(renewed.leaseUntil()).isAfter(lease.leaseUntil());
    assertThat(repository.isCurrent(renewed)).isTrue();
  }

  @Test
  @DisplayName("Should reject a signing lease with the wrong identity")
  void shouldRejectSigningLeaseWithWrongIdentity() {
    var current =
        repository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();
    var wrongOperation =
        CertificateSigningLease.builder()
            .operation(CertificateAuthorityOperation.ROTATION)
            .ownerId(current.ownerId())
            .fencingEpoch(current.fencingEpoch())
            .databaseTime(current.databaseTime())
            .leaseUntil(current.leaseUntil())
            .build();
    var wrongOwner =
        CertificateSigningLease.builder()
            .operation(current.operation())
            .ownerId(UUID.randomUUID())
            .fencingEpoch(current.fencingEpoch())
            .databaseTime(current.databaseTime())
            .leaseUntil(current.leaseUntil())
            .build();
    var wrongFence =
        CertificateSigningLease.builder()
            .operation(current.operation())
            .ownerId(current.ownerId())
            .fencingEpoch(current.fencingEpoch() + 1)
            .databaseTime(current.databaseTime())
            .leaseUntil(current.leaseUntil())
            .build();

    for (var stale : List.of(wrongOperation, wrongOwner, wrongFence)) {
      assertThat(repository.isCurrent(stale)).isFalse();
      assertThat(repository.renew(stale, LEASE_DURATION)).isEmpty();
      assertThat(repository.release(stale)).isFalse();
    }
    assertThat(repository.isCurrent(current)).isTrue();
  }

  @Test
  @DisplayName("Should refuse renewal when the lease expires while waiting for its row lock")
  void shouldRefuseRenewalWhenLeaseExpiresWhileWaitingForItsRowLock() throws Exception {
    var lease =
        repository
            .tryAcquire(CertificateAuthorityOperation.ISSUANCE, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();
    var expiresAt = databaseTime().plusSeconds(2);
    dsl.update(TRANSCODE_CA_SIGNING_LEASE)
        .set(TRANSCODE_CA_SIGNING_LEASE.LEASE_UNTIL, expiresAt)
        .execute();

    var rowLocked = new CountDownLatch(1);
    var releaseRow = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var blocker =
          executor.submit(
              () ->
                  dsl.transaction(
                      configuration -> {
                        DSL.using(configuration)
                            .selectOne()
                            .from(TRANSCODE_CA_SIGNING_LEASE)
                            .where(TRANSCODE_CA_SIGNING_LEASE.SINGLETON.isTrue())
                            .forUpdate()
                            .fetchSingle();
                        rowLocked.countDown();
                        releaseRow.await();
                      }));
      assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();

      var renewal = executor.submit(() -> repository.renew(lease, LEASE_DURATION));
      try {
        awaitBlockedLeaseStatement();
        awaitDatabaseTime(expiresAt);
      } finally {
        releaseRow.countDown();
      }

      assertThat(renewal.get(5, TimeUnit.SECONDS)).isEmpty();
      blocker.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  @DisplayName("Should advance the fence and reject the stale owner after expiry")
  void shouldAdvanceFenceAndRejectStaleOwnerAfterExpiry() {
    var stale =
        repository
            .tryAcquire(CertificateAuthorityOperation.ROTATION, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();
    dsl.update(TRANSCODE_CA_SIGNING_LEASE)
        .set(TRANSCODE_CA_SIGNING_LEASE.LEASE_UNTIL, databaseTime().minusSeconds(1))
        .execute();

    var current =
        repository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();

    assertThat(current.fencingEpoch()).isGreaterThan(stale.fencingEpoch());
    assertThat(repository.isCurrent(stale)).isFalse();
    assertThat(repository.renew(stale, LEASE_DURATION)).isEmpty();
    assertThat(repository.release(stale)).isFalse();
    assertThat(repository.isCurrent(current)).isTrue();
    assertThat(repository.release(current)).isTrue();
    assertThat(repository.isCurrent(current)).isFalse();
  }

  private OffsetDateTime databaseTime() {
    return dsl.select(statementTimestamp()).fetchSingle().value1();
  }

  private void awaitDatabaseTime(OffsetDateTime expected) {
    while (databaseTime().isBefore(expected)) {
      LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
    }
  }

  private void awaitBlockedLeaseStatement() {
    var waitEventType = DSL.field(DSL.name("wait_event_type"), String.class);
    var query = DSL.field(DSL.name("query"), String.class);
    var activity = DSL.table(DSL.name("pg_stat_activity"));
    var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      var blocked =
          dsl.selectCount()
              .from(activity)
              .where(waitEventType.eq("Lock"))
              .and(query.contains("transcode_ca_signing_lease"))
              .fetchSingle(0, int.class);
      if (blocked > 0) {
        return;
      }
      LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
    }
    throw new AssertionError("Signing lease statement did not wait for the row lock");
  }

  private org.jooq.Field<OffsetDateTime> statementTimestamp() {
    return DSL.function(DSL.name("statement_timestamp"), SQLDataType.TIMESTAMPWITHTIMEZONE);
  }
}
