package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.external.tmdb.TmdbSearchResults;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.metadata.ImageThumbnailWorkerVerticle;
import com.streamarr.server.services.metadata.TheMovieDatabaseService;
import com.streamarr.server.services.parsers.video.DefaultVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.VideoFileMetadata;
import com.streamarr.server.services.validation.VideoExtensionValidator;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.shareddata.SharedData;
import io.vertx.ext.web.client.HttpResponse;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.springframework.beans.factory.InitializingBean;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


// TODO: Implement, inspiration here https://gitlab.com/olaris/olaris-server/-/blob/develop/metadata/managers/library.go
@Service
@RequiredArgsConstructor
public class LibraryManagementService implements InitializingBean {

    private final VideoExtensionValidator videoExtensionValidator;
    private final DefaultVideoFileMetadataParser defaultVideoFileMetadataParser;

    private final TheMovieDatabaseService theMovieDatabaseService;
    private final LibraryRepository libraryRepository;
    private final MediaFileRepository mediaFileRepository;
    private final MovieRepository movieRepository;
    private final Logger log;
    private final Vertx vertx;
    private SharedData sharedData;

    public void afterPropertiesSet() {
        sharedData = vertx.sharedData();
    }

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

        var filesToMatchCounter = new AtomicInteger(0);

        try (var stream = Files.walk(Paths.get(library.getFilepath()))) {

            // TODO: count and save to JOB table or something?
            stream
                .filter(Files::isRegularFile)
                .map(Path::toFile)
                .forEach(file -> {
                    processFile(library, file, filesToMatchCounter);
                });

        } catch (InvalidPathException | IOException ex) {

            library.setStatus(LibraryStatus.UNHEALTHY);
            libraryRepository.save(library);

            log.error("Failed accessing " + library.getName() + " library filepath", ex);

            throw new RuntimeException("Failed to access library filepath.");
        }

        log.info("Scanned {} MediaFiles from Library at filepath: {}", filesToMatchCounter.get(), library.getFilepath());

        // DEFINITION: "media file" an entity that describes the file and
        // serves as an intermediate step until we can resolve metadata and link to parent (ex. Movie).

        // ensure "media file" isn't already in DB (Instead rely on DB constraint?)
        // "probe file"
        // save "media file"; series, season, episode, movie, song, etc.
        // get metadata using "media file".
    }

    private void processFile(Library library, File file, AtomicInteger counter) {

        if (isInvalidFileExtension(file)) {
            return;
        }

        var mediaFile = probeFile(library, file);

        if (isAlreadyMatched(mediaFile)) {
            return;
        }

        // Count files to match
        counter.getAndIncrement();

        var mediaInformationResult = extractInformationFromMediaFile(library.getType(), mediaFile);

        // TODO: Should this ever retry? Manual resolution
        if (mediaInformationResult.isEmpty()) {
            mediaFile.setStatus(MediaFileStatus.FILENAME_PARSING_FAILED);
            mediaFileRepository.save(mediaFile);

            return;
        }

        log.info("Parsed filename. Title: {} and Year: {}", mediaInformationResult.get().title(), mediaInformationResult.get().year());

        var searchResult = searchForMedia(mediaInformationResult.get());

        // TODO: Network, retry automatically? Will this error if no results are found?
        searchResult.onFailure(handler -> {
            mediaFile.setStatus(MediaFileStatus.SEARCH_FAILED);
            mediaFileRepository.saveAsync(mediaFile);

            // TODO: Better log
            log.error("Failed to identify media in search.");
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
            case SERIES, OTHER -> throw new IllegalStateException("Not yet supported.");
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

    private Optional<VideoFileMetadata> extractInformationFromMediaFile(MediaType libraryType, MediaFile mediaFile) {
        return switch (libraryType) {
            case MOVIE -> extractInformationAsMovieFile(mediaFile);
            default -> throw new IllegalStateException("Unexpected value: " + mediaFile);
        };
    }

    private Optional<VideoFileMetadata> extractInformationAsMovieFile(MediaFile mediaFile) {
        var result = defaultVideoFileMetadataParser.parse(mediaFile.getFilename());

        if (result.isEmpty() || StringUtils.isEmpty(result.get().title())) {
            return Optional.empty();
        }

        return result;
    }

    private <T> Future<HttpResponse<TmdbSearchResults>> searchForMedia(T information) {
        return switch (information) {
            case VideoFileMetadata videoFileMetadata -> theMovieDatabaseService.searchForMovie(videoFileMetadata);
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

        var fs = vertx.fileSystem();
        var movieCtx = new MovieContext();

        movieResult.onComplete(t -> {

            sharedData.getLocalLock(id, res -> {
                if (res.succeeded()) {
                    var lock = res.result();

                    var tmdbMovie = t.result().body();

                    movieCtx.setPosterPath(tmdbMovie.getPosterPath());
                    movieCtx.setBackdropPath(tmdbMovie.getBackdropPath());

                    movieRepository.findByTmdbId(id)
                        .compose(e -> {

                            // no movie found, creating new one
                            if (e == null) {
                                return movieRepository.saveAsync(Movie.builder()
                                    .libraryId(library.getId())
                                    .tmdbId(String.valueOf(tmdbMovie.getId()))
                                    .title(tmdbMovie.getTitle())
                                    .studios(tmdbMovie.getProductionCompanies().stream()
                                        .map(c -> Company.builder()
                                            .name(c.getName())
                                            .build())
                                        .collect(Collectors.toSet()))
                                    .build());
                            }

                            return Future.succeededFuture(e);
                        }).onComplete(m -> {

                            lock.release();

                            var movie = m.result();

                            // TODO: Find a better way around lifting up errors here?...
                            if (movie == null) {
                                log.error("movie was null");
                                return;
                            }

                            // TODO: Do I still need this?
                            mediaFile.setStatus(MediaFileStatus.MATCHED);

                            movie.addFile(mediaFile);

                            // Get and add people.
                            theMovieDatabaseService.getMovieCreditsMetadata(id).onComplete(r -> {
                                var creditsResponse = r.result().body();

                                // TODO: Do I need to update Entity, tmdb unique id?
                                creditsResponse.getCast()
                                    .stream()
                                    .map(credit -> Person.builder().name(credit.getName()).build())
                                    .forEach(movie::addPersonToCast);

                                movieRepository.saveAsync(movie);
                            }).onFailure(f -> movieRepository.saveAsync(movie));

                            // Get and save posters.
                            theMovieDatabaseService.getImage(movieCtx.getPosterPath())
                                .compose(r -> {
                                    var imageBuffer = r.body();

                                    // TODO: Improve this to handle multiple images and multiple thumbnails...
                                    vertx.eventBus()
                                        .request(ImageThumbnailWorkerVerticle.IMAGE_THUMBNAIL_PROCESSOR, imageBuffer)
                                        .compose(i -> fs.writeFile("/Users/stuckya/Downloads/Test/Images/" + movie.getId().toString() + "-poster-200px.jpg", (Buffer) i.body()))
                                        .onSuccess(i -> log.info("wrote thumbnail"))
                                        .onFailure(i -> log.error("failed to write thumbnail", i));

                                    return fs.writeFile("/Users/stuckya/Downloads/Test/Images/" + movie.getId().toString() + "-poster.jpg", imageBuffer);
                                })
                                .onSuccess(r -> log.info("Wrote original image"))
                                .onFailure(r -> log.error("Failed to write file:", r));
                        }).onFailure(ex -> {
                            lock.release();
                            log.error("failed to save movie", ex);
                        });
                } else {
                    log.info("failed to get lock", res.cause());
                }
            });
        });
    }

    private void deleteMissingMediaFiles() {
        // get all items in library
        // locate files in FS
        // cleanup if file cannot be located.
    }

    @Getter
    @Setter
    public class MovieContext {
        private String posterPath;
        private String backdropPath;
    }
}
