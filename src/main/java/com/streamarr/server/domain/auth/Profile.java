package com.streamarr.server.domain.auth;

import com.streamarr.server.domain.BaseAuditableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
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
 * A portable viewing identity (ADR 0024). {@code householdId} is {@code belongsTo}: the canonical
 * Household and teardown boundary — it does not make the Profile available or grant authority;
 * shares do that.
 */
@Entity
@Table(name = "profile")
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Profile extends BaseAuditableEntity<Profile> {

  private UUID householdId;

  private String name;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Builder.Default
  private ProfileKind kind = ProfileKind.ADULT;

  /** The optional Content Ceiling; null means unrestricted by this dimension. */
  private Integer maximumAllowedRatingAge;

  /** An effective PIN exists only when this is non-null and non-blank (the database agrees). */
  private String pinHash;

  private String picture;

  /** A restriction means supervision: Kid kind or any Content Ceiling. */
  public boolean isRestricted() {
    return isRestricted(kind, maximumAllowedRatingAge);
  }

  /** The one definition of "restricted" for every shape that carries a kind and a ceiling. */
  public static boolean isRestricted(ProfileKind kind, Integer maximumAllowedRatingAge) {
    return kind == ProfileKind.KID || maximumAllowedRatingAge != null;
  }

  public boolean hasEffectivePin() {
    return pinHash != null && !pinHash.isBlank();
  }
}
