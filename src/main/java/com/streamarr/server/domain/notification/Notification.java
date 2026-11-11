package com.streamarr.server.domain.notification;

import com.streamarr.server.domain.BaseAuditableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A stored note to one recipient Account that something happened to a subject (ADR 0063). The first
 * build's subject is one Profile share. {@code createdOn} is when the first event made it, and
 * {@code latestEventAt} moves with each later event of its kind. A request's resolution is never
 * stored: it is read from the share ({@link RequestResolution}).
 */
@Entity
@Table(name = "notification")
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Notification extends BaseAuditableEntity<Notification> {

  private UUID recipientAccountId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private NotificationKind kind;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private NotificationForm form;

  private UUID shareId;

  /** The Profile's name at the latest event, for a reader who may no longer see the Profile. */
  private String profileName;

  @Getter(AccessLevel.NONE)
  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private ProfileShareAnswer shareAnswer;

  @Getter(AccessLevel.NONE)
  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private ProfileShareEndedBy shareEndedBy;

  private Instant latestEventAt;

  @Getter(AccessLevel.NONE)
  private Instant readAt;

  public Optional<Instant> getReadAt() {
    return Optional.ofNullable(readAt);
  }

  public ProfileShareFacts getFacts() {
    return new ProfileShareFacts(
        profileName, Optional.ofNullable(shareAnswer), Optional.ofNullable(shareEndedBy));
  }
}
