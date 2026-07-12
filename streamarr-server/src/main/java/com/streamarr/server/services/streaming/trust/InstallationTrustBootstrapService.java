package com.streamarr.server.services.streaming.trust;

import com.streamarr.server.repositories.streaming.trust.CertificateAuthoritySigningLeaseRepository;
import com.streamarr.server.repositories.streaming.trust.InstallationTrustRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.RequiredArgsConstructor;

@Builder
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class InstallationTrustBootstrapService {

  private static final Duration DEFAULT_BOOTSTRAP_TIMEOUT = Duration.ofMinutes(2);
  private static final Duration INITIAL_RETRY_INTERVAL = Duration.ofMillis(100);
  private static final Duration MAXIMUM_RETRY_INTERVAL = Duration.ofSeconds(1);

  interface BootstrapTiming {

    long nanoTime();

    void parkNanos(long nanoseconds);
  }

  private static final BootstrapTiming SYSTEM_TIMING =
      new BootstrapTiming() {
        @Override
        public long nanoTime() {
          return System.nanoTime();
        }

        @Override
        public void parkNanos(long nanoseconds) {
          LockSupport.parkNanos(nanoseconds);
        }
      };

  private final InstallationTrustRepository trustRepository;
  private final CertificateAuthoritySigningLeaseRepository signingLeaseRepository;
  private final CertificateAuthorityStore authorityStore;
  private final BuiltInCertificateAuthority certificateAuthority;
  private final UUID ownerId;
  private final Duration leaseDuration;
  private final Duration bootstrapTimeout;
  private final BootstrapTiming timing;

  public InstallationTrust bootstrap() {
    requireNotInterrupted();
    requirePositiveLeaseDuration();
    var runtimeTiming = timing == null ? SYSTEM_TIMING : timing;
    var timeoutNanos = bootstrapTimeoutNanos();
    var startedAt = runtimeTiming.nanoTime();
    var retryIntervalNanos = INITIAL_RETRY_INTERVAL.toNanos();

    while (runtimeTiming.nanoTime() - startedAt < timeoutNanos) {
      var initialized = loadInitialized();
      requireNotInterrupted();
      if (initialized.isPresent()) {
        return initialized.orElseThrow();
      }

      var lease =
          signingLeaseRepository.tryAcquire(
              CertificateAuthorityOperation.BOOTSTRAP, ownerId, leaseDuration);
      if (lease.isPresent()) {
        var bootstrapped =
            bootstrapWithLease(
                lease.orElseThrow(), () -> runtimeTiming.nanoTime() - startedAt >= timeoutNanos);
        if (bootstrapped.isPresent()) {
          return bootstrapped.orElseThrow();
        }
      }

      requireNotInterrupted();
      var elapsed = runtimeTiming.nanoTime() - startedAt;
      var remaining = timeoutNanos - elapsed;
      if (remaining <= 0) {
        break;
      }
      runtimeTiming.parkNanos(Math.min(retryIntervalNanos, remaining));
      requireNotInterrupted();
      retryIntervalNanos = Math.min(retryIntervalNanos * 2, MAXIMUM_RETRY_INTERVAL.toNanos());
    }

    throw new InstallationTrustException(
        "Installation trust bootstrap did not obtain signing leadership");
  }

  private Optional<InstallationTrust> bootstrapWithLease(
      CertificateSigningLease lease, BooleanSupplier deadlineExceeded) {
    try {
      requireNotInterrupted();
      if (deadlineExceeded.getAsBoolean()) {
        return Optional.empty();
      }
      var initialized = loadInitialized();
      if (initialized.isPresent()) {
        return initialized;
      }
      if (deadlineExceeded.getAsBoolean()) {
        return Optional.empty();
      }

      var installationId = trustRepository.installationId();
      var material =
          authorityStore
              .load()
              .orElseGet(
                  () ->
                      authorityStore.createIfAbsent(
                          certificateAuthority.create(installationId, lease.databaseTime())));
      certificateAuthority.validate(material, installationId, trustRepository.databaseTime());
      requireNotInterrupted();

      if (!trustRepository.publishInitial(lease, InitialTrustPublication.from(material))) {
        return loadInitialized();
      }
      return Optional.of(
          loadInitialized()
              .orElseThrow(
                  () ->
                      new InstallationTrustException(
                          "Installation trust publication committed no active bundle")));
    } finally {
      signingLeaseRepository.release(lease);
    }
  }

  private Optional<InstallationTrust> loadInitialized() {
    return trustRepository
        .findInitialized()
        .map(
            trust -> {
              var material =
                  authorityStore
                      .load()
                      .orElseThrow(
                          () ->
                              new InstallationTrustException(
                                  "Initialized installation certificate authority secret is missing"));
              certificateAuthority.validate(
                  material, trust.installationId(), trustRepository.databaseTime());
              certificateAuthority.requireMatches(trust, material);
              return trust;
            });
  }

  private void requirePositiveLeaseDuration() {
    if (leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("Certificate authority signing lease must be positive");
    }
  }

  private long bootstrapTimeoutNanos() {
    var timeout = bootstrapTimeout == null ? DEFAULT_BOOTSTRAP_TIMEOUT : bootstrapTimeout;
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("Installation trust bootstrap timeout must be positive");
    }
    try {
      return timeout.toNanos();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("Installation trust bootstrap timeout is too large", e);
    }
  }

  private void requireNotInterrupted() {
    if (Thread.currentThread().isInterrupted()) {
      throw new InstallationTrustException("Installation trust bootstrap was interrupted");
    }
  }
}
