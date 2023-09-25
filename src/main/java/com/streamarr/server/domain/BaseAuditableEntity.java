package com.streamarr.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@SuperBuilder
@MappedSuperclass
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseAuditableEntity<T extends BaseAuditableEntity<T>> {

    @Id
    @Column(updatable = false)
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @CreatedBy
    @Column(updatable = false)
    private UUID createdBy;

    @CreatedDate
    @Column(updatable = false)
    @Setter(value = AccessLevel.PROTECTED)
    private Instant createdOn;

    @LastModifiedBy
    private UUID lastModifiedBy;

    @LastModifiedDate
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

    public abstract static class BaseAuditableEntityBuilder<T extends BaseAuditableEntity<T>, C extends BaseAuditableEntity<T>, B extends BaseAuditableEntityBuilder<T, C, B>> {

        private B createdOn(Instant createdOn) {
            throw new UnsupportedOperationException("createdOn method is unsupported");
        }

        private B lastModifiedOn(Instant lastModifiedOn) {
            throw new UnsupportedOperationException("lastModifiedOn method is unsupported");
        }
    }
}
