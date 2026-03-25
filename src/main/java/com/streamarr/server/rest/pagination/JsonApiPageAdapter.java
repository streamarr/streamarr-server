package com.streamarr.server.rest.pagination;

import com.streamarr.server.domain.BaseAuditableEntity;
import com.streamarr.server.domain.Collectable;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PageItem;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JsonApiPageAdapter {

  private final JsonApiCursorCodec cursorCodec;

  public <T extends BaseAuditableEntity<?>> JsonApiPageResponse toResponse(
      MediaPage<T> page,
      MediaPaginationOptions options,
      String baseUrl,
      int pageSize,
      String resourceType) {

    if (page.items().isEmpty()) {
      var links = new JsonApiLinks(buildFirstLink(baseUrl, pageSize), null, null);
      return new JsonApiPageResponse(links, List.of());
    }

    var data = page.items().stream().map(item -> toResource(item, options, resourceType)).toList();

    var firstCursor = data.getFirst().meta().page().cursor();
    var lastCursor = data.getLast().meta().page().cursor();

    var prevLink =
        page.hasPreviousPage()
            ? buildPageLink(baseUrl, pageSize, "page[before]", firstCursor)
            : null;

    var nextLink =
        page.hasNextPage() ? buildPageLink(baseUrl, pageSize, "page[after]", lastCursor) : null;

    var links = new JsonApiLinks(buildFirstLink(baseUrl, pageSize), prevLink, nextLink);

    return new JsonApiPageResponse(links, data);
  }

  private <T extends BaseAuditableEntity<?>> JsonApiResource toResource(
      PageItem<T> pageItem, MediaPaginationOptions options, String resourceType) {
    var entity = pageItem.item();
    Map<String, Object> attributes = new LinkedHashMap<>();

    if (entity instanceof Collectable collectable) {
      attributes.put("title", collectable.getTitle());
    }

    switch (entity) {
      case Movie movie -> {
        attributes.put("releaseDate", movie.getReleaseDate());
        attributes.put("summary", movie.getSummary());
      }
      case Series series -> {
        attributes.put("firstAirDate", series.getFirstAirDate());
        attributes.put("summary", series.getSummary());
      }
      default -> {
        // No additional attributes for other entity types
      }
    }

    var cursor = cursorCodec.encode(options, entity.getId(), pageItem.sortValue());
    var meta = new JsonApiResourceMeta(new JsonApiPageMeta(cursor));

    return new JsonApiResource(resourceType, entity.getId().toString(), attributes, meta);
  }

  private String buildFirstLink(String baseUrl, int pageSize) {
    return baseUrl + "?page[size]=" + pageSize;
  }

  private String buildPageLink(String baseUrl, int pageSize, String cursorParam, String cursor) {
    return baseUrl
        + "?page[size]="
        + pageSize
        + "&"
        + cursorParam
        + "="
        + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
  }
}
