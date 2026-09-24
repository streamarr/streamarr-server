package com.streamarr.server.fixtures;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;

public final class SavedMediaFixture {

  private SavedMediaFixture() {}

  public static Movie saveMovie(
      LibraryRepository libraryRepository, MovieRepository movieRepository) {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    return movieRepository.saveAndFlush(
        Movie.builder().title("Saved Movie").library(library).build());
  }
}
