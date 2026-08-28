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
 * A ServerAdmin's proposal to create an Account (ADR 0024 §Invitations). The row is retained for
 * reporting: snapshot columns keep what it meant while deleted targets go null. Only the SHA-256
 * digest of the code's secret is stored; the code itself is returned once at issuance.
 */
@Entity
@Table(name = "account_invitation")
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AccountInvitation extends BaseAuditableEntity<AccountInvitation>
    implements OneTimeCredential {

  private String recipientEmail;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Builder.Default
  private AccountInvitationMode mode = AccountInvitationMode.CREATE;

  /** The existing unlinked Profile a LINK invitation assigns; null for CREATE. */
  private UUID profileId;

  private UUID householdId;

  private String householdName;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private HouseholdRole householdRole;

  private String profileName;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private ProfileKind profileKind;

  private Integer maximumAllowedRatingAge;

  private UUID localManagerAccountId;

  private UUID issuerAccountId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Builder.Default
  private AccountInvitationStatus status = AccountInvitationStatus.PENDING;

  private Instant expiresAt;

  private Instant decidedAt;

  private String invalidationReason;

  private String publicId;

  private byte[] secretDigest;

  @Override
  public boolean isRedeemableAt(Instant now) {
    return status == AccountInvitationStatus.PENDING && expiresAt.isAfter(now);
  }

  /** Expiry is a predicate at read time: a stale PENDING row projects as EXPIRED. */
  public AccountInvitationStatus statusAt(Instant now) {
    if (status == AccountInvitationStatus.PENDING && !isRedeemableAt(now)) {
      return AccountInvitationStatus.EXPIRED;
    }

    return status;
  }

  @Override
  public String toString() {
    return "AccountInvitation[id=%s, status=%s, publicId=%s, secretDigest=REDACTED]"
        .formatted(getId(), status, publicId);
  }
}
