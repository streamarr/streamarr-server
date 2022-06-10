package com.streamarr.server.domain.media;

import com.streamarr.server.domain.BaseEntity;
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
public class Release extends BaseEntity<Release> {

    private UUID movieId;

    // File? or List<File>?
    private String path;

}
