package com.streamarr.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Getter
@Setter
@SuperBuilder(toBuilder = true)
@MappedSuperclass
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseAuditableEntity<T extends BaseAuditableEntity<T>> {

  @Id
  @Column(updatable = false)
  @GeneratedValue(strategy = GenerationType.AUTO)
  private UUID id;

  // The audit trigger fills these columns from the transaction's actor; Hibernate only reads them.
  // The protected setters exist for test fakes that play the database.
  @Generated
  @Column(insertable = false, updatable = false)
  @Setter(value = AccessLevel.PROTECTED)
  private UUID createdBy;

  @Generated
  @Column(insertable = false, updatable = false)
  @Setter(value = AccessLevel.PROTECTED)
  private Instant createdOn;

  @Generated(event = {EventType.INSERT, EventType.UPDATE})
  @Column(insertable = false, updatable = false)
  @Setter(value = AccessLevel.PROTECTED)
  private UUID lastModifiedBy;

  @Generated(event = {EventType.INSERT, EventType.UPDATE})
  @Column(insertable = false, updatable = false)
  @Setter(value = AccessLevel.PROTECTED)
  private Instant lastModifiedOn;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    T that = (T) o;

    return id != null && id.equals(that.getId());
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }

  public abstract static class BaseAuditableEntityBuilder<
      T extends BaseAuditableEntity<T>,
      C extends BaseAuditableEntity<T>,
      B extends BaseAuditableEntityBuilder<T, C, B>> {

    private B createdBy(UUID createdBy) {
      return self();
    }

    private B createdOn(Instant createdOn) {
      return self();
    }

    private B lastModifiedBy(UUID lastModifiedBy) {
      return self();
    }

    private B lastModifiedOn(Instant lastModifiedOn) {
      return self();
    }
  }
}
