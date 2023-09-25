package com.streamarr.server.domain.media;

import com.streamarr.server.domain.BaseAuditableEntity;
import io.hypersistence.utils.hibernate.type.basic.PostgreSQLEnumType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;

import java.util.UUID;

@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class MediaFile extends BaseAuditableEntity<MediaFile> {

    private UUID mediaId;

    @Enumerated(EnumType.STRING)
    @Type(PostgreSQLEnumType.class)
    private MediaFileStatus status;

    private UUID libraryId;

    private String filename;
    private String filepath;

    private long size;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        MediaFile that = (MediaFile) o;

        return filepath != null && filepath.equals(that.getFilepath());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
