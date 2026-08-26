package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.CredentialAttemptAdmission;
import com.streamarr.server.domain.auth.CredentialAttemptPolicy;
import com.streamarr.server.domain.auth.CredentialAttemptReservation;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.exceptions.CredentialAttemptUnavailableException;
import com.streamarr.server.repositories.auth.CredentialAttemptRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

@Tag("UnitTest")
@DisplayName("Credential Attempt Gate Tests")
class CredentialAttemptGateTest {

  private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
  private static final CredentialAttemptTarget TARGET =
      CredentialAttemptTarget.builder()
          .kind(CredentialKind.ACCOUNT_LOGIN)
          .accountId(UUID.fromString("10000000-0000-0000-0000-000000000001"))
          .ipAddress("192.0.2.30")
          .build();

  @Test
  @DisplayName("Should fail closed when reserving an attempt cannot reach the database")
  void shouldFailClosedWhenReservingAttemptCannotReachDatabase() {
    var gate = gate(new FailingRepository(true));

    assertThatThrownBy(() -> gate.reserve(TARGET))
        .isInstanceOf(CredentialAttemptUnavailableException.class)
        .hasCauseInstanceOf(DataAccessResourceFailureException.class);
  }

  @Test
  @DisplayName("Should fail closed when completing an attempt cannot reach the database")
  void shouldFailClosedWhenCompletingAttemptCannotReachDatabase() {
    var gate = gate(new FailingRepository(false));
    var reservation = gate.reserve(TARGET);

    assertThatThrownBy(() -> gate.complete(reservation, CredentialAttemptResult.SUCCEEDED))
        .isInstanceOf(CredentialAttemptUnavailableException.class)
        .hasCauseInstanceOf(DataAccessResourceFailureException.class);
  }

  private static CredentialAttemptGate gate(CredentialAttemptRepository repository) {
    return new CredentialAttemptGate(
        repository, _ -> new CredentialAttemptPolicy.Unlimited(), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static final class FailingRepository implements CredentialAttemptRepository {

    private final boolean failReservation;

    private FailingRepository(boolean failReservation) {
      this.failReservation = failReservation;
    }

    @Override
    public CredentialAttemptAdmission reserve(
        CredentialAttemptTarget target, CredentialAttemptPolicy policy, Instant attemptedAt) {
      if (failReservation) {
        throw failure();
      }

      return new CredentialAttemptAdmission.Reserved(
          new CredentialAttemptReservation(UUID.randomUUID(), target));
    }

    @Override
    public void complete(
        CredentialAttemptReservation reservation,
        CredentialAttemptResult result,
        Instant completedAt) {
      throw failure();
    }

    @Override
    public int deleteAttemptedBefore(Instant cutoff) {
      return 0;
    }

    private static DataAccessResourceFailureException failure() {
      return new DataAccessResourceFailureException("database unavailable");
    }
  }
}
