package com.streamarr.server.domain.media;

import com.streamarr.server.domain.BaseEntity;
import com.vladmihalcea.hibernate.type.basic.PostgreSQLEnumType;
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
public class MediaFile extends BaseEntity<MediaFile> {

    private UUID mediaId;
    @Enumerated(EnumType.STRING)
    @Type(PostgreSQLEnumType.class)
    private MediaFileStatus status;
    private UUID libraryId;
    private String filename;
    private String filepath;
    private long size;
}
