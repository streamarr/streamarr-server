package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.DeviceAuthorization;
import com.streamarr.server.domain.auth.DeviceAuthorizationStatus;
import com.streamarr.server.repositories.auth.DeviceAuthorizationDecisionCommand;
import com.streamarr.server.repositories.auth.DeviceAuthorizationInsertCommand;
import com.streamarr.server.repositories.auth.DeviceAuthorizationInsertResult;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

public class FakeDeviceAuthorizationRepository extends FakeJpaRepository<DeviceAuthorization>
    implements DeviceAuthorizationRepository {

  /** Mirrors uq_device_authorization_device_code_digest and uq_device_authorization_user_code. */
  @Override
  public synchronized <S extends DeviceAuthorization> S save(S authorization) {
    requireUnique(
        authorization,
        DeviceAuthorization::getDeviceCodeDigest,
        "uq_device_authorization_device_code_digest");
    requireUnique(
        authorization, DeviceAuthorization::getUserCode, "uq_device_authorization_user_code");
    return super.save(authorization);
  }

  @Override
  public Optional<DeviceAuthorization> findByUserCode(String userCode) {
    return database.values().stream()
        .filter(authorization -> userCode.equals(authorization.getUserCode()))
        .findFirst();
  }

  /**
   * The real query takes a row lock; this mirrors the serialization it buys, which is the property
   * the poll state machine depends on.
   */
  @Override
  public synchronized Optional<DeviceAuthorization> lockByDeviceCodeDigest(String digest) {
    return database.values().stream()
        .filter(authorization -> digest.equals(authorization.getDeviceCodeDigest()))
        .findFirst();
  }

  /** Mirrors the conditional single-statement decision write. */
  @Override
  public synchronized int decide(DeviceAuthorizationDecisionCommand command) {
    var match =
        findByUserCode(command.userCode())
            .filter(authorization -> authorization.getStatus() == DeviceAuthorizationStatus.PENDING)
            .filter(authorization -> !authorization.hasExpiredAt(command.now()));

    if (match.isEmpty()) {
      return 0;
    }

    var authorization = match.get();
    authorization.setStatus(command.status());
    authorization.setDecidedByAccountId(command.decidedByAccountId());
    authorization.setDecidedAt(command.now());
    return 1;
  }

  @Override
  public synchronized void updateCadence(
      UUID id, int pollIntervalSeconds, Instant nextPollAt, Instant now) {
    findById(id)
        .ifPresent(
            authorization -> {
              authorization.setPollIntervalSeconds(pollIntervalSeconds);
              authorization.setNextPollAt(nextPollAt);
            });
  }

  @Override
  public synchronized void markConsumed(UUID id, Instant now) {
    findById(id).ifPresent(a -> a.setStatus(DeviceAuthorizationStatus.CONSUMED));
  }

  /** Mirrors the advisory-locked count-and-insert: the cap is checked and taken indivisibly. */
  @Override
  public synchronized DeviceAuthorizationInsertResult tryInsertWithinCap(
      DeviceAuthorizationInsertCommand command) {
    var outstanding = countOutstanding(command.now());
    if (outstanding >= command.maxOutstanding()) {
      return new DeviceAuthorizationInsertResult(false, outstanding);
    }

    save(
        DeviceAuthorization.builder()
            .deviceCodeDigest(command.deviceCodeDigest())
            .userCode(command.userCode())
            .status(DeviceAuthorizationStatus.PENDING)
            .deviceName(command.deviceName())
            .expiresAt(command.expiresAt())
            .nextPollAt(command.nextPollAt())
            .pollIntervalSeconds(command.pollIntervalSeconds())
            .build());
    return new DeviceAuthorizationInsertResult(true, outstanding + 1);
  }

  @Override
  public int countOutstanding(Instant now) {
    return (int) outstanding(now).count();
  }

  @Override
  public Optional<Instant> findOldestOutstandingExpiry(Instant now) {
    return outstanding(now).map(DeviceAuthorization::getExpiresAt).min(Comparator.naturalOrder());
  }

  @Override
  public synchronized int deleteExpired(Instant cutoff) {
    var expired =
        database.values().stream().filter(a -> a.hasExpiredAt(cutoff)).map(a -> a.getId()).toList();
    expired.forEach(database::remove);
    return expired.size();
  }

  @Override
  public Optional<DeviceAuthorizationStatus> findStatusByUserCode(String userCode) {
    return findByUserCode(userCode).map(DeviceAuthorization::getStatus);
  }

  private Stream<DeviceAuthorization> outstanding(Instant now) {
    return database.values().stream()
        .filter(a -> a.getStatus() == DeviceAuthorizationStatus.PENDING)
        .filter(a -> !a.hasExpiredAt(now));
  }

  private void requireUnique(
      DeviceAuthorization authorization,
      Function<DeviceAuthorization, Object> column,
      String constraint) {
    var value = column.apply(authorization);
    if (value == null) {
      return;
    }

    var conflicts =
        database.values().stream()
            .anyMatch(
                existing ->
                    !existing.getId().equals(authorization.getId())
                        && value.equals(column.apply(existing)));
    if (conflicts) {
      var message = "duplicate key value violates unique constraint \"%s\"".formatted(constraint);
      throw new DataIntegrityViolationException(
          message,
          new ConstraintViolationException(
              message, new SQLException(message, "23505"), constraint));
    }
  }
}
