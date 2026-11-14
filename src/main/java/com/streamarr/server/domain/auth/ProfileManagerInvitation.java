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
 * A manager's proposal to add another eligible Account as a direct ProfileManager (ADR 0024
 * §ProfileManager). The row is retained for reporting: snapshot columns keep what it meant while
 * deleted targets go null. Only the SHA-256 digest of the code's secret is stored; the code itself
 * is returned once at issuance.
 */
@Entity
@Table(name = "profile_manager_invitation")
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ProfileManagerInvitation extends BaseAuditableEntity<ProfileManagerInvitation> {

  private UUID profileId;

  private String profileName;

  private UUID inviterAccountId;

  private String inviterDisplayName;

  private UUID recipientAccountId;

  private String recipientEmail;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Builder.Default
  private ProfileManagerInvitationStatus status = ProfileManagerInvitationStatus.PENDING;

  private Instant expiresAt;

  private Instant decidedAt;

  private String invalidationReason;

  /**
   * When the server invalidated the invitation because of what became of its invited Account. The
   * status keeps what the Profile's ProfileManagers read, to whom the invitation still waits.
   */
  private Instant recipientInvalidatedAt;

  private String recipientInvalidationReason;

  private String publicId;

  private byte[] secretDigest;

  /**
   * The status as the Profile's ProfileManagers read it (ADR 0024 §Invitations): an invitation
   * invalidated because of what became of its invited Account still waits to them. Expiry is a
   * predicate at read time: a stale PENDING row projects as EXPIRED.
   */
  public ProfileManagerInvitationStatus statusAt(Instant now) {
    if (status == ProfileManagerInvitationStatus.PENDING && !expiresAt.isAfter(now)) {
      return ProfileManagerInvitationStatus.EXPIRED;
    }

    return status;
  }

  /** Whether the Account is the one the invitation names, the only one that may answer it. */
  public boolean isAddressedTo(UUID accountId) {
    return accountId.equals(recipientAccountId);
  }

  /** The status as its invited Account and ServerAdmin read it, who see every invalidation. */
  public ProfileManagerInvitationStatus statusToItsAccountAt(Instant now) {
    if (recipientInvalidatedAt != null) {
      return ProfileManagerInvitationStatus.INVALIDATED;
    }

    return statusAt(now);
  }

  @Override
  public String toString() {
    return "ProfileManagerInvitation[id=%s, status=%s, publicId=%s, secretDigest=REDACTED]"
        .formatted(getId(), status, publicId);
  }
}
