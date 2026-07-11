package com.streamarr.server.services.metadata;

import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.tmdb.TmdbCredit;
import com.streamarr.server.services.metadata.tmdb.TmdbGenre;
import com.streamarr.server.services.metadata.tmdb.TmdbProductionCompany;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

public final class TmdbMetadataMapper {

  private TmdbMetadataMapper() {}

  public static List<ImageSource> buildPosterAndBackdropSources(
      String posterPath, String backdropPath) {
    var sources = new ArrayList<ImageSource>();

    if (StringUtils.isNotBlank(posterPath)) {
      sources.add(new TmdbImageSource(ImageType.POSTER, posterPath));
    }
    if (StringUtils.isNotBlank(backdropPath)) {
      sources.add(new TmdbImageSource(ImageType.BACKDROP, backdropPath));
    }

    return sources;
  }

  public static Map<String, List<ImageSource>> buildPersonImageSources(
      List<TmdbCredit> castList, List<TmdbCredit> crewList) {
    var sources = new HashMap<String, List<ImageSource>>();

    for (var credit : castList) {
      addPersonImageSource(sources, credit);
    }
    for (var crew : crewList) {
      if ("Director".equals(crew.getJob())) {
        addPersonImageSource(sources, crew);
      }
    }

    return sources;
  }

  public static Map<String, List<ImageSource>> buildCompanyImageSources(
      List<TmdbProductionCompany> companies) {
    var sources = new HashMap<String, List<ImageSource>>();

    for (var company : companies) {
      if (StringUtils.isNotBlank(company.getLogoPath())) {
        sources
            .computeIfAbsent(String.valueOf(company.getId()), k -> new ArrayList<>())
            .add(new TmdbImageSource(ImageType.LOGO, company.getLogoPath()));
      }
    }

    return sources;
  }

  public static Set<Company> mapCompanies(List<TmdbProductionCompany> companies) {
    return companies.stream()
        .map(c -> Company.builder().sourceId(String.valueOf(c.getId())).name(c.getName()).build())
        .collect(Collectors.toSet());
  }

  public static List<Person> mapCast(List<TmdbCredit> castList) {
    return castList.stream()
        .map(
            credit ->
                Person.builder()
                    .sourceId(String.valueOf(credit.getId()))
                    .name(credit.getName())
                    .build())
        .collect(Collectors.toList());
  }

  public static List<Person> mapDirectors(List<TmdbCredit> crewList) {
    return crewList.stream()
        .filter(crew -> "Director".equals(crew.getJob()))
        .map(
            crew ->
                Person.builder()
                    .sourceId(String.valueOf(crew.getId()))
                    .name(crew.getName())
                    .build())
        .collect(Collectors.toList());
  }

  public static Set<Genre> mapGenres(List<TmdbGenre> genres) {
    return genres.stream()
        .map(g -> Genre.builder().sourceId(String.valueOf(g.getId())).name(g.getName()).build())
        .collect(Collectors.toSet());
  }

  private static void addPersonImageSource(
      Map<String, List<ImageSource>> sources, TmdbCredit credit) {
    if (StringUtils.isNotBlank(credit.getProfilePath())) {
      sources
          .computeIfAbsent(String.valueOf(credit.getId()), k -> new ArrayList<>())
          .add(new TmdbImageSource(ImageType.PROFILE, credit.getProfilePath()));
    }
  }
}
