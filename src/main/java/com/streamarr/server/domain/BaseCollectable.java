package com.streamarr.server.domain;

import com.streamarr.server.domain.media.MediaFile;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.OneToMany;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;


@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@Getter
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public abstract class BaseCollectable<T extends BaseCollectable<T>> extends BaseEntity<T> implements Collectable {

    private UUID libraryId;

    private String title;

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "mediaId")
    private final Set<MediaFile> files = new HashSet<>();
}
