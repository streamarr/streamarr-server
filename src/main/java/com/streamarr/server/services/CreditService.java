package com.streamarr.server.services;

import java.util.List;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreditService {

  private final PersonService personService;
  private final GenreService genreService;
  private final CompanyService companyService;

  /**
   * Finds or creates every credited person, genre and studio. Every transaction inserts people
   * first, then genres, then studios, each table in {@link CreditInsertOrder}. Cast and directors
   * go in one pass, and the returned cast and directors keep their listed order.
   */
  @Transactional
  public MediaTitleCredits getOrCreateCredits(@NonNull MediaTitleCreditsRequest request) {
    var credits = request.credits();
    var imageRefreshMode = request.imageRefreshMode();

    var castCount = credits.cast().size();
    var people =
        personService.getOrCreatePersons(
            Stream.concat(credits.cast().stream(), credits.directors().stream()).toList(),
            request.personImageSources(),
            imageRefreshMode);
    var cast = List.copyOf(people.subList(0, castCount));
    var directors = List.copyOf(people.subList(castCount, people.size()));
    var genres = genreService.getOrCreateGenres(credits.genres());
    var studios =
        companyService.getOrCreateCompanies(
            credits.studios(), request.companyImageSources(), imageRefreshMode);

    return MediaTitleCredits.builder()
        .cast(cast)
        .directors(directors)
        .genres(genres)
        .studios(studios)
        .build();
  }
}
