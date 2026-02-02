package com.streamarr.server.config;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.ContentRating;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevDataInitializer {

  private final LibraryRepository libraryRepository;
  private final MovieRepository movieRepository;
  private final PersonRepository personRepository;

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void seed() {
    if (libraryRepository.count() > 0) {
      log.info("Database already seeded, skipping DevDataInitializer.");
      return;
    }

    log.info("Seeding development data...");

    var library =
        libraryRepository.save(
            Library.builder()
                .name("Movies")
                .backend(LibraryBackend.LOCAL)
                .status(LibraryStatus.HEALTHY)
                .filepath("/mpool/media/clean/movies")
                .externalAgentStrategy(ExternalAgentStrategy.TMDB)
                .type(MediaType.MOVIE)
                .build());

    var nolan =
        personRepository.save(Person.builder().sourceId("525").name("Christopher Nolan").build());

    var dicaprio =
        personRepository.save(Person.builder().sourceId("6193").name("Leonardo DiCaprio").build());

    var hardy = personRepository.save(Person.builder().sourceId("2524").name("Tom Hardy").build());

    movieRepository.save(
        Movie.builder()
            .title("Inception")
            .summary(
                "A thief who steals corporate secrets through dream-sharing technology is given the inverse task of planting an idea into the mind of a C.E.O.")
            .tagline("Your mind is the scene of the crime.")
            .releaseDate(LocalDate.of(2010, 7, 16))
            .contentRating(new ContentRating("MPAA", "PG-13", "US"))
            .library(library)
            .externalIds(
                Set.of(
                    ExternalIdentifier.builder()
                        .externalSourceType(ExternalSourceType.TMDB)
                        .externalId("27205")
                        .build(),
                    ExternalIdentifier.builder()
                        .externalSourceType(ExternalSourceType.IMDB)
                        .externalId("tt1375666")
                        .build()))
            .cast(List.of(dicaprio, hardy))
            .build());

    movieRepository.save(
        Movie.builder()
            .title("The Dark Knight")
            .summary(
                "When the menace known as the Joker wreaks havoc and chaos on the people of Gotham, Batman must accept one of the greatest psychological and physical tests of his ability to fight injustice.")
            .tagline("Why so serious?")
            .releaseDate(LocalDate.of(2008, 7, 18))
            .contentRating(new ContentRating("MPAA", "PG-13", "US"))
            .library(library)
            .externalIds(
                Set.of(
                    ExternalIdentifier.builder()
                        .externalSourceType(ExternalSourceType.TMDB)
                        .externalId("155")
                        .build()))
            .cast(List.of(hardy))
            .build());

    log.info("Development data seeded: 1 library, 2 movies, 3 people.");
  }
}
