package com.streamarr.server.domain;

import com.streamarr.server.domain.media.MediaType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.OneToMany;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Getter
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class Library extends BaseEntity<Library> {

    private String filepath;
    private String name;

    private Instant refreshStartedOn;
    private Instant refreshCompletedOn;

    @Enumerated(EnumType.STRING)
    private LibraryStatus status;

    @Enumerated(EnumType.STRING)
    private LibraryBackend backend;

    @Enumerated(EnumType.STRING)
    private MediaType type;

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "libraryId")
    private Set<BaseCollectable> items = new HashSet<>();

}
