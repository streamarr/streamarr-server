package com.streamarr.server.domain.media;

import com.streamarr.server.domain.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;

import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import java.util.UUID;

@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class MediaFile extends BaseEntity<MediaFile> {

    private UUID mediaId;
    @Enumerated(EnumType.STRING)
    @Type(type = "pgsql_enum")
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
}
