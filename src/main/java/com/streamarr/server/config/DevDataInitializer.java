package com.streamarr.server.config;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.ContentRating;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.domain.metadata.Rating;
import com.streamarr.server.domain.metadata.Review;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.RatingRepository;
import com.streamarr.server.repositories.ReviewRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
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
  private final CompanyRepository companyRepository;
  private final RatingRepository ratingRepository;
  private final ReviewRepository reviewRepository;
  private final MediaFileRepository mediaFileRepository;

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
        personRepository.save(
            Person.builder().sourceId("6193").name("Leonardo DiCaprio").build());

    var hardy =
        personRepository.save(Person.builder().sourceId("2524").name("Tom Hardy").build());

    var bale =
        personRepository.save(Person.builder().sourceId("3894").name("Christian Bale").build());

    var warnerBros =
        companyRepository.save(
            Company.builder().sourceId("174").name("Warner Bros. Pictures").build());

    var legendaryPictures =
        companyRepository.save(
            Company.builder().sourceId("923").name("Legendary Pictures").build());

    var inception =
        movieRepository.save(
            Movie.builder()
                .title("Inception")
                .summary(
                    "A thief who steals corporate secrets through dream-sharing technology is given the inverse task of planting an idea into the mind of a C.E.O.")
                .tagline("Your mind is the scene of the crime.")
                .releaseDate(LocalDate.of(2010, 7, 16))
                .contentRating(new ContentRating("MPAA", "PG-13", "US"))
                .backdropPath("/s3TBrRGB1iav7gFOCNx3H31MoES.jpg")
                .posterPath("/ljsZTbVsrQSqZgWeep2B1QiDKuh.jpg")
                .library(library)
                .studios(Set.of(warnerBros, legendaryPictures))
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
                .cast(List.of(dicaprio, hardy, nolan))
                .build());

    var darkKnight =
        movieRepository.save(
            Movie.builder()
                .title("The Dark Knight")
                .summary(
                    "When the menace known as the Joker wreaks havoc and chaos on the people of Gotham, Batman must accept one of the greatest psychological and physical tests of his ability to fight injustice.")
                .tagline("Why so serious?")
                .releaseDate(LocalDate.of(2008, 7, 18))
                .contentRating(new ContentRating("MPAA", "PG-13", "US"))
                .backdropPath("/nMKdUUepR0i5zn0y1T4CsSB5eff.jpg")
                .posterPath("/qJ2tW6WMUDux911BTUgMe1nPC7j.jpg")
                .library(library)
                .studios(Set.of(warnerBros, legendaryPictures))
                .externalIds(
                    Set.of(
                        ExternalIdentifier.builder()
                            .externalSourceType(ExternalSourceType.TMDB)
                            .externalId("155")
                            .build()))
                .cast(List.of(bale, hardy, nolan))
                .build());

    ratingRepository.saveAll(
        List.of(
            Rating.builder().movie(inception).source("TMDB").value("8.4").build(),
            Rating.builder().movie(darkKnight).source("TMDB").value("8.5").build()));

    reviewRepository.saveAll(
        List.of(
            Review.builder().movie(inception).author("Dev Reviewer").build(),
            Review.builder().movie(darkKnight).author("Dev Reviewer").build()));

    mediaFileRepository.saveAll(
        List.of(
            MediaFile.builder()
                .mediaId(inception.getId())
                .libraryId(library.getId())
                .status(MediaFileStatus.MATCHED)
                .filename("Inception (2010).mkv")
                .filepath("/mpool/media/clean/movies/Inception (2010)/Inception (2010).mkv")
                .size(4_500_000_000L)
                .build(),
            MediaFile.builder()
                .mediaId(darkKnight.getId())
                .libraryId(library.getId())
                .status(MediaFileStatus.MATCHED)
                .filename("The Dark Knight (2008).mkv")
                .filepath(
                    "/mpool/media/clean/movies/The Dark Knight (2008)/The Dark Knight (2008).mkv")
                .size(5_200_000_000L)
                .build()));

    log.info(
        "Development data seeded: 1 library, 2 movies, 4 people, 2 companies, 2 ratings, 2 reviews, 2 media files.");
  }
}
