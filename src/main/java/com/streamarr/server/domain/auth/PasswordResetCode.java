package com.streamarr.server.domain.auth;

import com.streamarr.server.domain.BaseAuditableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A ServerAdmin-issued single-use password-reset code (ADR 0024 §Account): redeemable while the
 * Account is disabled, never revealing the old secret, deleted with its Account. Only the SHA-256
 * digest of the code's secret is stored.
 */
@Entity
@Table(name = "password_reset_code")
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetCode extends BaseAuditableEntity<PasswordResetCode> {

  private UUID accountId;

  private UUID issuerAccountId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Builder.Default
  private PasswordResetCodeStatus status = PasswordResetCodeStatus.PENDING;

  private Instant expiresAt;

  private Instant redeemedAt;

  private String invalidationReason;

  private String publicId;

  private byte[] secretDigest;

  public PasswordResetCodeStatus statusAt(Instant now) {
    if (status == PasswordResetCodeStatus.PENDING && !expiresAt.isAfter(now)) {
      return PasswordResetCodeStatus.EXPIRED;
    }

    return status;
  }

  @Override
  public String toString() {
    return "PasswordResetCode[id=%s, status=%s, publicId=%s, secretDigest=REDACTED]"
        .formatted(getId(), status, publicId);
  }
}
