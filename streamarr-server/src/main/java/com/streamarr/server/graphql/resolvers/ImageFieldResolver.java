package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.graphql.dataloaders.ImageLoaderKey;
import com.streamarr.server.graphql.dto.ImageDto;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.dataloader.DataLoader;

@DgsComponent
public class ImageFieldResolver {

  @DgsData(parentType = "Movie", field = "images")
  public CompletableFuture<List<ImageDto>> movieImages(DataFetchingEnvironment dfe) {
    Movie movie = dfe.getSource();
    return loadImages(dfe, movie.getId(), ImageEntityType.MOVIE);
  }

  @DgsData(parentType = "Series", field = "images")
  public CompletableFuture<List<ImageDto>> seriesImages(DataFetchingEnvironment dfe) {
    Series series = dfe.getSource();
    return loadImages(dfe, series.getId(), ImageEntityType.SERIES);
  }

  @DgsData(parentType = "Season", field = "images")
  public CompletableFuture<List<ImageDto>> seasonImages(DataFetchingEnvironment dfe) {
    Season season = dfe.getSource();
    return loadImages(dfe, season.getId(), ImageEntityType.SEASON);
  }

  @DgsData(parentType = "Episode", field = "images")
  public CompletableFuture<List<ImageDto>> episodeImages(DataFetchingEnvironment dfe) {
    Episode episode = dfe.getSource();
    return loadImages(dfe, episode.getId(), ImageEntityType.EPISODE);
  }

  @DgsData(parentType = "Person", field = "images")
  public CompletableFuture<List<ImageDto>> personImages(DataFetchingEnvironment dfe) {
    Person person = dfe.getSource();
    return loadImages(dfe, person.getId(), ImageEntityType.PERSON);
  }

  @DgsData(parentType = "Company", field = "images")
  public CompletableFuture<List<ImageDto>> companyImages(DataFetchingEnvironment dfe) {
    Company company = dfe.getSource();
    return loadImages(dfe, company.getId(), ImageEntityType.COMPANY);
  }

  private CompletableFuture<List<ImageDto>> loadImages(
      DataFetchingEnvironment dfe, UUID entityId, ImageEntityType entityType) {
    DataLoader<ImageLoaderKey, List<ImageDto>> loader = dfe.getDataLoader("images");
    var key = new ImageLoaderKey(entityId, entityType);
    String typeArg = dfe.getArgument("type");

    if (typeArg == null) {
      return loader.load(key);
    }

    var imageType = ImageType.valueOf(typeArg);
    return loader
        .load(key)
        .thenApply(images -> images.stream().filter(img -> img.imageType() == imageType).toList());
  }
}
