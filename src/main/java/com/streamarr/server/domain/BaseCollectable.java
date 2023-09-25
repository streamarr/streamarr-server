package com.streamarr.server.domain;

import com.streamarr.server.domain.media.MediaFile;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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


@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Inheritance(strategy = InheritanceType.JOINED)
public abstract class BaseCollectable<T extends BaseCollectable<T>> extends BaseAuditableEntity<T> implements Collectable {

    private String title;

    @ManyToOne
    @JoinColumn(name = "libraryId")
    private Library library;

    @Builder.Default
    @OneToMany(
        cascade = {CascadeType.PERSIST, CascadeType.MERGE},
        fetch = FetchType.LAZY)
    @JoinColumn(name = "mediaId")
    @Setter(AccessLevel.NONE)
    private final Set<MediaFile> files = new HashSet<>();

    @Builder.Default
    @OneToMany(
        cascade = {CascadeType.PERSIST, CascadeType.MERGE},
        fetch = FetchType.LAZY)
    @JoinColumn(name = "entityId")
    @Setter(AccessLevel.NONE)
    private final Set<ExternalIdentifier> externalIds = new HashSet<>();

    public void addFile(MediaFile file) {
        file.setMediaId(this.getId());
        files.add(file);
    }
}
