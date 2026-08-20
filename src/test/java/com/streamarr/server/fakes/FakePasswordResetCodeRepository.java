package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.PasswordResetCodeStatus;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

public class FakePasswordResetCodeRepository extends FakeJpaRepository<PasswordResetCode>
    implements PasswordResetCodeRepository {

  @Override
  public Optional<PasswordResetCode> findByPublicId(String publicId) {
    return database.values().stream()
        .filter(code -> publicId.equals(code.getPublicId()))
        .findFirst();
  }

  @Override
  public boolean tryRedeem(UUID codeId, Instant now) {
    var redeemed =
        findById(codeId)
            .filter(code -> code.getStatus() == PasswordResetCodeStatus.PENDING)
            .filter(code -> code.getExpiresAt().isAfter(now));
    redeemed.ifPresent(
        code -> {
          code.setStatus(PasswordResetCodeStatus.REDEEMED);
          code.setRedeemedAt(now);
        });
    return redeemed.isPresent();
  }

  @Override
  public int invalidatePendingForAccount(UUID accountId, String reason, Instant now) {
    return invalidate(code -> accountId.equals(code.getAccountId()), reason);
  }

  @Override
  public int invalidateIssuedBy(UUID issuerAccountId, String reason, Instant now) {
    return invalidate(code -> issuerAccountId.equals(code.getIssuerAccountId()), reason);
  }

  @Override
  public int sweepExpired(Instant now) {
    var expired =
        database.values().stream()
            .filter(code -> code.getStatus() == PasswordResetCodeStatus.PENDING)
            .filter(code -> !code.getExpiresAt().isAfter(now))
            .toList();
    expired.forEach(code -> code.setStatus(PasswordResetCodeStatus.EXPIRED));
    return expired.size();
  }

  private int invalidate(Predicate<PasswordResetCode> scope, String reason) {
    var affected =
        database.values().stream()
            .filter(code -> code.getStatus() == PasswordResetCodeStatus.PENDING)
            .filter(scope)
            .toList();
    affected.forEach(
        code -> {
          code.setStatus(PasswordResetCodeStatus.INVALIDATED);
          code.setInvalidationReason(reason);
        });
    return affected.size();
  }
}
