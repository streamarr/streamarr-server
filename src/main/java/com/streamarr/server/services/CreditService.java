package com.streamarr.server.services;

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

  /** Finds or creates every credited person, genre and studio in the caller's transaction. */
  @Transactional
  public MediaTitleCredits getOrCreateCredits(@NonNull MediaTitleCreditsRequest request) {
    var credits = request.credits();
    var imageRefreshMode = request.imageRefreshMode();

    var cast =
        personService.getOrCreatePersons(
            credits.cast(), request.personImageSources(), imageRefreshMode);
    var directors =
        personService.getOrCreatePersons(
            credits.directors(), request.personImageSources(), imageRefreshMode);
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
