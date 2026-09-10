package com.streamarr.server.domain.media;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record MediaFileStreamId(
    @Column(name = "media_file_id") UUID mediaFileId,
    @Column(name = "stream_index") int streamIndex)
    implements Serializable {}
