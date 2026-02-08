package com.streamarr.server.services;

import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GenreService {

  private final GenreRepository genreRepository;
  private final MutexFactory<String> mutexFactory;

  public GenreService(GenreRepository genreRepository, MutexFactoryProvider mutexFactoryProvider) {
    this.genreRepository = genreRepository;
    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
  }

  @Transactional
  public Set<Genre> getOrCreateGenres(Set<Genre> genres) {
    return genres.stream().map(this::findOrCreateGenre).collect(Collectors.toSet());
  }

  private Genre findOrCreateGenre(Genre genre) {
    var mutex = mutexFactory.getMutex(genre.getSourceId());

    try {
      mutex.lock();

      var existing = genreRepository.findBySourceId(genre.getSourceId());

      if (existing.isPresent()) {
        var target = existing.get();
        target.setName(genre.getName());
        return genreRepository.save(target);
      }

      return genreRepository.save(genre);
    } finally {
      if (mutex.isHeldByCurrentThread()) {
        mutex.unlock();
      }
    }
  }
}
