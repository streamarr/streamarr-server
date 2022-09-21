package com.streamarr.server.domain;

import com.streamarr.server.domain.media.MediaFile;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import javax.persistence.CascadeType;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Inheritance;
import javax.persistence.InheritanceType;
import javax.persistence.OneToMany;
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
    @Setter(AccessLevel.NONE)
    @OneToMany(
        cascade = {CascadeType.PERSIST, CascadeType.MERGE},
        fetch = FetchType.LAZY,
        mappedBy = "mediaId")
    private final Set<MediaFile> files = new HashSet<>();

    public void addFile(MediaFile file) {
        file.setMediaId(this.getId());
        files.add(file);
    }
}
