package com.streamarr.server.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import javax.persistence.Entity;
import javax.persistence.Inheritance;
import javax.persistence.InheritanceType;
import java.util.UUID;


@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@Getter
@SuperBuilder
@NoArgsConstructor
public abstract class BaseCollectable<T extends BaseCollectable<T>> extends BaseEntity<T> implements Collectable {

    private UUID libraryId;

    private String title;
}
