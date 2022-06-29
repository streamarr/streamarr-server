package com.streamarr.server.domain.media;

import com.streamarr.server.domain.BaseEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import javax.persistence.MappedSuperclass;

@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public class MediaFile extends BaseEntity<MediaFile> {

    private String filename;
    private String path;
    private long size;
    // TODO: duration?
}
