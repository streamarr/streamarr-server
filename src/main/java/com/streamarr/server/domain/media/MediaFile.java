package com.streamarr.server.domain.media;

import com.streamarr.server.domain.BaseEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import javax.persistence.MappedSuperclass;
import java.util.UUID;

@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public class MediaFile extends BaseEntity<MediaFile> {

    private UUID libraryId;
    private String filename;
    private String filepath;
    private long size;
}
