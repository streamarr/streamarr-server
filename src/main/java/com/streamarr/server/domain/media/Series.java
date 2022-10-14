package com.streamarr.server.domain.media;

import com.streamarr.server.domain.BaseCollectable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import javax.persistence.Entity;


@Entity
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Series extends BaseCollectable<Series> {

    private String backdropPath;

    private String logoPath;

    private String posterPath;
}
