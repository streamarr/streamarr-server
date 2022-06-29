package com.streamarr.server.domain.media;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import javax.persistence.Entity;
import java.util.UUID;

@Entity
@Getter
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class MovieFile extends MediaFile {

    private UUID movieId;
    // TODO: join back to Movie? join to Library?
}
