package com.streamarr.server.domain.containers;

import com.streamarr.server.domain.BaseCollectable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import javax.persistence.Entity;
import javax.persistence.OneToMany;
import java.util.HashSet;
import java.util.Set;

@Entity
@Getter
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class Collection extends BaseCollectable {

    @OneToMany
    private Set<BaseCollectable> items = new HashSet<>();
}
