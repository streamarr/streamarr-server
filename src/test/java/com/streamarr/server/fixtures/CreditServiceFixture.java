package com.streamarr.server.fixtures;

import com.streamarr.server.fakes.FakeCompanyRepository;
import com.streamarr.server.fakes.FakeGenreRepository;
import com.streamarr.server.fakes.FakePersonRepository;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.services.ArtworkRequestService;
import com.streamarr.server.services.CompanyService;
import com.streamarr.server.services.CreditService;
import com.streamarr.server.services.GenreService;
import com.streamarr.server.services.PersonService;
import lombok.Builder;

public final class CreditServiceFixture {

  private CreditServiceFixture() {}

  /** Unset collaborators default to empty in-memory repositories and in-memory artwork services. */
  @Builder(builderMethodName = "creditServiceBuilder")
  private static CreditService creditService(
      PersonRepository personRepository,
      GenreRepository genreRepository,
      CompanyRepository companyRepository,
      ArtworkRequestService artworkRequestService) {
    var people = personRepository;
    if (people == null) {
      people = new FakePersonRepository();
    }

    var genres = genreRepository;
    if (genres == null) {
      genres = new FakeGenreRepository();
    }

    var companies = companyRepository;
    if (companies == null) {
      companies = new FakeCompanyRepository();
    }

    var artwork = artworkRequestService;
    if (artwork == null) {
      artwork = ArtworkFixture.builder().build().artworkRequestService();
    }

    return new CreditService(
        new PersonService(people, artwork),
        new GenreService(genres),
        new CompanyService(companies, artwork));
  }
}
