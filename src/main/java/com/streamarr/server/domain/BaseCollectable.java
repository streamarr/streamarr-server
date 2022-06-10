package com.streamarr.server.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import javax.persistence.Entity;
import javax.persistence.Inheritance;
import javax.persistence.InheritanceType;


@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@Getter
@SuperBuilder
@NoArgsConstructor
public class BaseCollectable extends BaseEntity<BaseCollectable> implements Collectable {

    private String title;

}
