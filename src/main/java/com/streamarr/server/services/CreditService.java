package com.streamarr.server.services;

import com.streamarr.server.repositories.LibraryOperationRepository;
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
  private final LibraryOperationRepository libraryOperations;

  /**
   * Finds or creates every credited person, genre and studio. Every transaction inserts people
   * first, then genres, then studios, each table in {@link CreditInsertOrder}. Cast and directors
   * go in one pass, and the returned cast and directors keep their listed order. Credits that a
   * stopped library operation saves request no person or company artwork (ADR 0027).
   */
  @Transactional
  public MediaTitleCredits getOrCreateCredits(@NonNull MediaTitleCreditsRequest creditsRequest) {
    var request = creditsRequest;
    if (isSavedByStoppedLibraryOperation(request)) {
      request = request.withoutArtwork();
    }

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

  private boolean isSavedByStoppedLibraryOperation(MediaTitleCreditsRequest request) {
    return request.libraryOperationId().filter(libraryOperations::hasStoredStop).isPresent();
  }
}
