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
 * A code that a HouseholdAdmin, or a ServerAdmin, issues for one Household so that whoever holds it
 * may offer a Profile there (ADR 0024 §Share codes). Only the SHA-256 digest of the normalised code
 * is stored; the code itself is returned once, when it is issued. A deleted issuer goes null.
 */
@Entity
@Table(name = "share_code")
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ShareCode extends BaseAuditableEntity<ShareCode> {

  private UUID householdId;

  private UUID issuerAccountId;

  private byte[] codeDigest;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Builder.Default
  private ShareCodeStatus status = ShareCodeStatus.LIVE;

  private Instant expiresAt;

  private Instant endedAt;

  /**
   * LIVE and unexpired at {@code now}: the only state in which a presented code admits an offer.
   */
  public boolean isLiveAt(Instant now) {
    return status == ShareCodeStatus.LIVE && expiresAt.isAfter(now);
  }

  /** Expiry is a predicate at read time: a LIVE row past its expiry reads as EXPIRED. */
  public ShareCodeStatus statusAt(Instant now) {
    if (status == ShareCodeStatus.LIVE && !isLiveAt(now)) {
      return ShareCodeStatus.EXPIRED;
    }

    return status;
  }

  @Override
  public String toString() {
    return "ShareCode[id=%s, householdId=%s, status=%s, codeDigest=REDACTED]"
        .formatted(getId(), householdId, status);
  }
}
