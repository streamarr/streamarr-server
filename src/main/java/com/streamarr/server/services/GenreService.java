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
    if (genres == null) {
      return Set.of();
    }

    return genres.stream().map(this::findOrCreateGenre).collect(Collectors.toSet());
  }

  private Genre findOrCreateGenre(Genre genre) {
    if (genre.getSourceId() == null) {
      throw new IllegalArgumentException("Genre sourceId must not be null");
    }

    var existing = genreRepository.findBySourceId(genre.getSourceId());

    if (existing.isPresent()) {
      var target = existing.get();
      target.setName(genre.getName());
      return genreRepository.save(target);
    }

    return genreRepository.save(genre);
  }
}
