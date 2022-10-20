package com.streamarr.server.domain;

import com.streamarr.server.domain.media.MediaFile;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.OneToMany;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;


@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@Getter
@Setter
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public abstract class BaseCollectable<T extends BaseCollectable<T>> extends BaseEntity<T> implements Collectable {

    private UUID libraryId;

    private String title;

    @Builder.Default
    @OneToMany(
        cascade = {CascadeType.PERSIST, CascadeType.MERGE},
        fetch = FetchType.LAZY,
        mappedBy = "mediaId")
    @Setter(AccessLevel.NONE)
    private final Set<MediaFile> files = new HashSet<>();

    public void addFile(MediaFile file) {
        file.setMediaId(this.getId());
        files.add(file);
    }
}
