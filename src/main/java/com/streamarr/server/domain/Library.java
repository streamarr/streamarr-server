package com.streamarr.server.domain;

import com.streamarr.server.domain.media.MediaType;
import com.vladmihalcea.hibernate.type.basic.PostgreSQLEnumType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Getter
@Setter
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class Library extends BaseEntity<Library> {

    private String filepath;

    private String name;

    private Instant refreshStartedOn;

    private Instant refreshCompletedOn;

    @Enumerated(EnumType.STRING)
    @Type(PostgreSQLEnumType.class)
    private LibraryStatus status;

    @Enumerated(EnumType.STRING)
    @Type(PostgreSQLEnumType.class)
    private LibraryBackend backend;

    // Library should only contain a single type
    @Enumerated(EnumType.STRING)
    @Type(PostgreSQLEnumType.class)
    private MediaType type;

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "libraryId")
    private Set<BaseCollectable<?>> items = new HashSet<>();
}
