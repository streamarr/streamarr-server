package com.streamarr.server.services;

import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.repositories.GenreRepository;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GenreService {

  private final GenreRepository genreRepository;

  @Transactional
  public Set<Genre> getOrCreateGenres(Set<Genre> genres) {
    return genres.stream().map(this::saveGenre).collect(Collectors.toSet());
  }

  private Genre saveGenre(Genre genre) {
    var existing = genreRepository.findBySourceId(genre.getSourceId());
    return existing.orElseGet(() -> genreRepository.save(genre));
  }
}
