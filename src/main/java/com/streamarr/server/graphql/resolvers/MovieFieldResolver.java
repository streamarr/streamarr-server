package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.domain.metadata.Rating;
import com.streamarr.server.domain.metadata.Review;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.RatingRepository;
import com.streamarr.server.repositories.ReviewRepository;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class MovieFieldResolver {

  private final CompanyRepository companyRepository;
  private final PersonRepository personRepository;
  private final GenreRepository genreRepository;
  private final RatingRepository ratingRepository;
  private final ReviewRepository reviewRepository;

  @DgsData(parentType = "Movie", field = "studios")
  public List<Company> studios(DataFetchingEnvironment dfe) {
    Movie movie = dfe.getSource();
    return companyRepository.findByMovieId(movie.getId());
  }

  @DgsData(parentType = "Movie", field = "cast")
  public List<Person> cast(DataFetchingEnvironment dfe) {
    Movie movie = dfe.getSource();
    return personRepository.findCastByMovieId(movie.getId());
  }

  @DgsData(parentType = "Movie", field = "directors")
  public List<Person> directors(DataFetchingEnvironment dfe) {
    Movie movie = dfe.getSource();
    return personRepository.findDirectorsByMovieId(movie.getId());
  }

  @DgsData(parentType = "Movie", field = "genres")
  public List<Genre> genres(DataFetchingEnvironment dfe) {
    Movie movie = dfe.getSource();
    return genreRepository.findByMovieId(movie.getId());
  }

  @DgsData(parentType = "Movie", field = "ratings")
  public List<Rating> ratings(DataFetchingEnvironment dfe) {
    Movie movie = dfe.getSource();
    return ratingRepository.findByMovie_Id(movie.getId());
  }

  @DgsData(parentType = "Movie", field = "reviews")
  public List<Review> reviews(DataFetchingEnvironment dfe) {
    Movie movie = dfe.getSource();
    return reviewRepository.findByMovie_Id(movie.getId());
  }
}
