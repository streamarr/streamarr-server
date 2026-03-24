package com.streamarr.server.rest;

import com.streamarr.server.exceptions.InvalidPaginationArgumentException;
import com.streamarr.server.rest.pagination.JsonApiCursorCodec;
import com.streamarr.server.rest.pagination.JsonApiPageAdapter;
import com.streamarr.server.rest.pagination.JsonApiPageResponse;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api", produces = "application/vnd.api+json")
@RequiredArgsConstructor
public class MovieRestController {

  private final MovieService movieService;
  private final PaginationService paginationService;
  private final JsonApiCursorCodec cursorCodec;
  private final JsonApiPageAdapter pageAdapter;

  @GetMapping("/libraries/{id}/movies")
  public ResponseEntity<JsonApiPageResponse> getMovies(
      @PathVariable UUID id,
      @RequestParam(name = "page[size]", defaultValue = "20") int pageSize,
      @RequestParam(name = "page[after]", required = false) String after,
      @RequestParam(name = "page[before]", required = false) String before,
      HttpServletRequest request) {

    var isBackward = before != null;
    var paginationOptions =
        paginationService.getPaginationOptions(
            isBackward ? 0 : pageSize, after, isBackward ? pageSize : 0, before);

    var filter = MediaFilter.builder().libraryId(id).build();
    var options = buildMediaPaginationOptions(paginationOptions, filter);

    var page = movieService.getMoviesWithFilter(options);
    var baseUrl = request.getRequestURL().toString();
    var response = pageAdapter.toResponse(page, options, baseUrl, pageSize, "movies");

    return ResponseEntity.ok(response);
  }

  @ExceptionHandler({InvalidPaginationArgumentException.class, IllegalArgumentException.class})
  public ResponseEntity<String> handleBadRequest(RuntimeException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
  }

  private MediaPaginationOptions buildMediaPaginationOptions(
      PaginationOptions paginationOptions, MediaFilter filter) {
    if (paginationOptions.getCursor().isEmpty()) {
      return MediaPaginationOptions.builder()
          .paginationOptions(paginationOptions)
          .mediaFilter(filter)
          .build();
    }

    var decoded = cursorCodec.decode(paginationOptions);
    cursorCodec.validateCursorFilter(decoded, filter);
    return decoded;
  }
}
