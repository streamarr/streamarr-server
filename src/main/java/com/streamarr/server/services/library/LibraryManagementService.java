package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.external.tmdb.TmdbSearchResults;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.movie.MediaFileRepository;
import com.streamarr.server.repositories.movie.MovieRepository;
import com.streamarr.server.services.extraction.video.VideoFilenameExtractionService;
import com.streamarr.server.services.metadata.TheMovieDatabaseService;
import com.streamarr.server.utils.VideoExtensionValidator;
import io.vertx.core.Future;
import io.vertx.ext.web.client.HttpResponse;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;


// TODO: Implement, inspiration here https://gitlab.com/olaris/olaris-server/-/blob/develop/metadata/managers/library.go
@Service
@RequiredArgsConstructor
public class LibraryManagementService {

    private final VideoExtensionValidator videoExtensionValidator;
    private final VideoFilenameExtractionService videoFilenameExtractionService;

    private final TheMovieDatabaseService theMovieDatabaseService;
    private final LibraryRepository libraryRepository;
    private final MediaFileRepository mediaFileRepository;
    private final MovieRepository movieRepository;
    private final Logger log;

    public void addLibrary() {
        // validate
        // save new "Library" entity
        // refreshAll
        // register watcher
    }

    public void removeLibrary() {
        // remove watcher
        // cleanup?
        // terminate streams?
        // remove all db entries but leave files (shows, movies, etc)
        // delete "Library" entity
    }

    public void refreshLibrary(UUID libraryId) {
        var optionalLibrary = libraryRepository.findById(libraryId);

        if (optionalLibrary.isEmpty()) {
            throw new RuntimeException("Library cannot be found for refresh.");
        }

        var library = optionalLibrary.get();

//        if (library.getStatus().equals(LibraryStatus.SCANNING)) {
//            throw new RuntimeException("Library scan already in progress.");
//        }

        // TODO: deleteMissingMediaFiles() - check all files in the database to ensure they still exist

        log.info("Starting " + library.getName() + " library scan.");
        var startTime = Instant.now();

        library.setStatus(LibraryStatus.SCANNING);
        library.setRefreshStartedOn(startTime);
        libraryRepository.save(library);

        try (var stream = Files.walk(Paths.get(library.getFilepath()))) {

            stream
                .filter(Files::isRegularFile)
                .map(Path::toFile)
                .forEach(file -> processFile(library, file));

        } catch (InvalidPathException | IOException ex) {
            library.setStatus(LibraryStatus.UNHEALTHY);
            libraryRepository.save(library);

            log.error("Failed accessing " + library.getName() + " library filepath", ex);

            throw new RuntimeException("Failed to access library filepath.");
        }

        // DEFINITION: "media file" an entity that describes the file and
        // serves as an intermediate step until we can resolve metadata and link to parent (ex. Movie).

        // ensure "media file" isn't already in DB (Instead rely on DB constraint?)
        // "probe file"
        // save "media file"; series, season, episode, movie, song, etc.
        // get metadata using "media file".
    }

    private void processFile(Library library, File file) {

        if (isInvalidFileExtension(file)) {
            return;
        }

        var mediaFile = probeFile(library, file);

        if (isAlreadyMatched(mediaFile)) {
            return;
        }

        var mediaInformationResult = extractInformationFromMediaFile(library.getType(), mediaFile);

        // TODO: Should this ever retry? Manual resolution
        if (mediaInformationResult.isEmpty()) {
            mediaFile.setStatus(MediaFileStatus.FILENAME_PARSING_FAILED);
            mediaFileRepository.save(mediaFile);

            return;
        }

        var searchResult = searchForMedia(mediaInformationResult.get());

        // TODO: Network, retry automatically? Will this error if no results are found?
        searchResult.onFailure(handler -> {
            mediaFile.setStatus(MediaFileStatus.MEDIA_SEARCH_FAILED);
            mediaFileRepository.save(mediaFile);

            // TODO: Better log
            log.error("Failed search for media");
        });

        searchResult.onSuccess(handler -> {
            // TODO: Better way to get result from array? Do we need to handle no results here?
            var firstResult = handler.body().getResults().get(0);
            enrichMediaMetadata(library, mediaFile, firstResult.getId());
        });
    }

    private boolean isInvalidFileExtension(File file) {
        var extension = getExtension(file);

        return !videoExtensionValidator.validate(extension);
    }

    private String getExtension(File file) {
        return FilenameUtils.getExtension(file.getName());
    }

    private MediaFile probeFile(Library library, File file) {
        return switch (library.getType()) {
            case MOVIE -> probeMovie(library, file);
            case SERIES, OTHER -> null;
        };
    }

    private MediaFile probeMovie(Library library, File file) {
        var optionalMovieFile = mediaFileRepository.findFirstByFilepath(file.getAbsolutePath());

        if (optionalMovieFile.isPresent()) {
            log.info("MediaFile id: " + optionalMovieFile.get().getId() + " already exists, not adding again.");
            return optionalMovieFile.get();
        }

        return mediaFileRepository.save(MediaFile.builder()
            .status(MediaFileStatus.UNMATCHED)
            .filename(file.getName())
            .filepath(file.getAbsolutePath())
            .size(file.length())
            .libraryId(library.getId())
            .build());
    }

    private MediaFile probeEpisode() {
        return null;
        // TODO: implement
    }

    private boolean isAlreadyMatched(MediaFile file) {
        return file.getStatus().equals(MediaFileStatus.MATCHED);
    }

    private Optional<VideoFilenameExtractionService.Result> extractInformationFromMediaFile(MediaType libraryType, MediaFile mediaFile) {
        return switch (libraryType) {
            case MOVIE -> extractInformationAsMovieFile(mediaFile);
            default -> throw new IllegalStateException("Unexpected value: " + mediaFile);
        };
    }

    private Optional<VideoFilenameExtractionService.Result> extractInformationAsMovieFile(MediaFile mediaFile) {
        var result = videoFilenameExtractionService.extract(mediaFile.getFilename());

        if (result.isEmpty() || StringUtils.isEmpty(result.get().title())) {
            return Optional.empty();
        }

        return result;
    }

    private <T> Future<HttpResponse<TmdbSearchResults>> searchForMedia(T information) {
        return switch (information) {
            case VideoFilenameExtractionService.Result result -> theMovieDatabaseService.searchForMovie(result);
            default -> throw new IllegalStateException("Unexpected value: " + information);
        };
    }

    private void enrichMediaMetadata(Library library, MediaFile mediaFile, int id) {
        switch (library.getType()) {
            case MOVIE -> enrichMovieMetadata(library, mediaFile, String.valueOf(id));
            default -> throw new IllegalStateException("Unexpected value: " + mediaFile);
        }
        ;
    }

    private void enrichMovieMetadata(Library library, MediaFile mediaFile, String id) {
        var movieResult = theMovieDatabaseService.getMovieMetadata(String.valueOf(id));

        movieResult.onFailure(handler -> {
            // TODO: Better log
            log.error("Failed to enrich movie.", handler.getCause());
        });

        movieResult.onSuccess(handler -> {
            var movieResponse = handler.body();

            // TODO: Fix Hibernate cascade?
            var movie = movieRepository.save(Movie.builder()
                .libraryId(library.getId())
                .tmdbId(String.valueOf(movieResponse.getId()))
                .title(movieResponse.getTitle())
                .build());

            mediaFile.setStatus(MediaFileStatus.MATCHED);
            mediaFile.setMediaId(movie.getId());

            mediaFileRepository.save(mediaFile);

        });
    }

    private void deleteMissingMediaFiles() {
        // get all items in library
        // locate files in FS
        // cleanup if file cannot be located.
    }
}
