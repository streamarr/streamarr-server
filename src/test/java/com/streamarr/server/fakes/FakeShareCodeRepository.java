package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.ShareCode;
import com.streamarr.server.domain.auth.ShareCodeFilter;
import com.streamarr.server.domain.auth.ShareCodeStatus;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.repositories.auth.ShareCodeRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Share codes in memory, with the database's single-winner endings and unique digests. */
public class FakeShareCodeRepository extends FakeJpaRepository<ShareCode>
    implements ShareCodeRepository {

  @Override
  public synchronized Optional<UUID> tryInsert(ShareCode unsaved) {
    if (findByCodeDigest(unsaved.getCodeDigest()).isPresent()) {
      return Optional.empty();
    }

    return Optional.of(
        save(unsaved.toBuilder().id(null).status(ShareCodeStatus.LIVE).endedAt(null).build())
            .getId());
  }

  @Override
  public boolean trySpend(UUID shareCodeId, Instant now) {
    return endLive(shareCodeId, ShareCodeStatus.SPENT, now);
  }

  @Override
  public boolean tryWithdraw(UUID shareCodeId, Instant now) {
    return endLive(shareCodeId, ShareCodeStatus.WITHDRAWN, now);
  }

  @Override
  public boolean tryInvalidate(UUID shareCodeId, Instant now) {
    return endLive(shareCodeId, ShareCodeStatus.INVALIDATED, now);
  }

  @Override
  public synchronized int invalidateLiveIssuedBy(UUID issuerAccountId, Instant now) {
    var invalidated = 0;
    for (var shareCode : List.copyOf(database.values())) {
      if (issuerAccountId.equals(shareCode.getIssuerAccountId())
          && endLive(shareCode.getId(), ShareCodeStatus.INVALIDATED, now)) {
        invalidated++;
      }
    }

    return invalidated;
  }

  @Override
  public List<Row<ShareCode>> rowsInHousehold(ShareCodeFilter filter, Seek seek) {
    return InMemoryKeyset.byCreationTime(
        database.values().stream()
            .filter(shareCode -> filter.householdId().equals(shareCode.getHouseholdId()))
            .filter(
                shareCode ->
                    filter.statuses().isEmpty()
                        || filter.statuses().contains(shareCode.statusAt(filter.at())))
            .toList(),
        seek);
  }

  @Override
  public Optional<ShareCode> findByIdAndReloadFromDatabase(UUID shareCodeId) {
    return findById(shareCodeId);
  }

  @Override
  public Optional<ShareCode> findByCodeDigest(byte[] codeDigest) {
    return database.values().stream()
        .filter(shareCode -> Arrays.equals(shareCode.getCodeDigest(), codeDigest))
        .findFirst();
  }

  @Override
  public List<ShareCode> findByIssuerAccountIdAndStatus(
      UUID issuerAccountId, ShareCodeStatus status) {
    return database.values().stream()
        .filter(shareCode -> issuerAccountId.equals(shareCode.getIssuerAccountId()))
        .filter(shareCode -> shareCode.getStatus() == status)
        .toList();
  }

  private synchronized boolean endLive(UUID shareCodeId, ShareCodeStatus ended, Instant now) {
    var live = findById(shareCodeId).filter(shareCode -> shareCode.isLiveAt(now));
    live.ifPresent(
        shareCode -> {
          shareCode.setStatus(ended);
          shareCode.setEndedAt(now);
        });
    return live.isPresent();
  }
}
