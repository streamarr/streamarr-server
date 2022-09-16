package com.streamarr.server.domain.external.tmdb;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class TmdbCredits {
    
    private int id;
    private List<TmdbCredit> cast;
    private List<TmdbCredit> crew;
}
