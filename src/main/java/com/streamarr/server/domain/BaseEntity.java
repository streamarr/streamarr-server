package com.streamarr.server.domain;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.Column;
import javax.persistence.EntityListeners;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity<T extends BaseEntity<T>> {

    @Id
    @Column(updatable = false)
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(updatable = false)
    @CreatedBy
    private UUID createdBy;

    @Column(updatable = false)
    @Setter(value = AccessLevel.PROTECTED)
    @CreatedDate
    private Instant createdOn;

    @LastModifiedBy
    private UUID lastModifiedBy;

    @Setter(value = AccessLevel.PROTECTED)
    @LastModifiedDate
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

    public static abstract class BaseEntityBuilder<T extends BaseEntity<T>, C extends BaseEntity<T>, B extends BaseEntityBuilder<T, C, B>> {

        private B createdOn(Instant createdOn) {
            throw new UnsupportedOperationException("createdOn method is unsupported");
        }

        private B lastModifiedOn(Instant lastModifiedOn) {
            throw new UnsupportedOperationException("lastModifiedOn method is unsupported");
        }
    }
}
