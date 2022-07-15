package com.streamarr.server.services.library;

import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.common.EntityStreamingSupport;
import akka.http.javadsl.common.JsonEntityStreamingSupport;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.Query;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.unmarshalling.Unmarshaller;
import akka.japi.Pair;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.external.tmdb.TmdbSearchResults;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MovieFile;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.movie.MovieFileRepository;
import com.streamarr.server.services.extraction.video.VideoFilenameExtractionService;
import com.streamarr.server.services.metadata.TheMovieDatabaseService;
import com.streamarr.server.utils.VideoExtensionValidator;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import scala.Int;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;


// TODO: Implement, inspiration here https://gitlab.com/olaris/olaris-server/-/blob/develop/metadata/managers/library.go
@Service
@RequiredArgsConstructor
public class LibraryManagementService {

    private final VideoExtensionValidator videoExtensionValidator;
    @Value("${tmdb.api.key:}")
    private String tmdbApiKey;
    private final VideoFilenameExtractionService videoFilenameExtractionService;

    private final TheMovieDatabaseService theMovieDatabaseService;
    private final LibraryRepository libraryRepository;
    private final MovieFileRepository movieFileRepository;
    private final ActorSystem actorSystem;
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

    public void refreshLibrary(UUID libraryId, boolean vertx) {
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

        Path rootPath;
        try {
            rootPath = Paths.get(library.getFilepath());
        } catch (InvalidPathException ex) {
            library.setStatus(LibraryStatus.UNHEALTHY);
            libraryRepository.save(library);

            log.error("Failed accessing " + library.getName() + " library filepath", ex);

            throw new RuntimeException("Failed to access library filepath.");
        }

        Source.fromJavaStream(() -> Files.walk(rootPath))
            .filter(Files::isRegularFile)
            .map(Path::toFile)
            .filter(file -> {
                var extension = getExtension(file);

                return videoExtensionValidator.validate(extension);
            })
            .map(file -> probeMovieSync(library, file))
            .filter(this::filterOutMatchedMediaFiles)
            .map(this::searchForMovieVertx)
            .map(result -> result.compose(res -> {
                    if (res.getLeft() == null) {
                        System.out.println("Couldn't parse title for: " + res.getRight().getFilename());
                        return Future.succeededFuture("Title not found.");
                    }

                    if (res.getLeft().getResults().size() > 0) {
                        System.out.println(res.getLeft().getResults().get(0).getTitle());
                        return Future.succeededFuture(res.getLeft().getResults().get(0).getTitle());
                    }

                    return Future.succeededFuture("Not found.");
                })
            )
            .log("error logging")
            .runWith(Sink.seq(), actorSystem)
            .whenComplete((action, fail) -> {


                var test = action.stream().map(f -> (Future) f).collect(Collectors.toList());

                CompositeFuture.all(test).onComplete(ar -> {
                    if (ar.succeeded()) {
                        var completeTime = Instant.now();
                        var runTime = Duration.between(startTime, completeTime);
                        
                        log.info("Completed refresh in: " + DurationFormatUtils.formatDuration(runTime.toMillis(), "**mm:ss:SS**", true) + ".");

                        library.setStatus(LibraryStatus.HEALTHY);
                        library.setRefreshCompletedOn(completeTime);
                        libraryRepository.save(library);
                    }
                });
            });


        // DEFINITION: "media file" an entity that describes the file and
        // serves as an intermediate step until we can resolve metadata and link to parent (ex. Movie).

        // ensure "media file" isn't already in DB (Instead rely on DB constraint?)
        // "probe file"
        // save "media file"; series, season, episode, movie, song, etc.
        // get metadata using "media file".
    }

    private MediaFile probeFile(Library library, File file) {
        return switch (library.getType()) {
            case MOVIE -> probeMovieSync(library, file);
            case SERIES, OTHER -> null;
        };
    }

    private <T> boolean filterOutMatchedMediaFiles(T file) {
        return switch (file) {
            case MovieFile movieFile -> movieFile.getMovieId() == null;
            default -> throw new IllegalStateException("Unexpected value: " + file);
        };
    }

    private MediaFile probeMovieSync(Library library, File file) {
        var optionalMovieFile = movieFileRepository.findFirstByFilepath(file.getAbsolutePath());

        if (optionalMovieFile.isPresent()) {
            log.info("MovieFile id: " + optionalMovieFile.get().getMovieId() + " already exists, not adding again.");
            return optionalMovieFile.get();
        }

        return movieFileRepository.save(MovieFile.builder()
            .filename(file.getName())
            .filepath(file.getAbsolutePath())
            .size(file.length())
            .libraryId(library.getId())
            .build());
    }

    private CompletionStage<ImmutablePair<TmdbSearchResults, MediaFile>> searchForMovie(MediaFile movieFile) {
        var result = videoFilenameExtractionService.extract(movieFile.getFilename());

        if (result.isEmpty()) {
            return CompletableFuture.completedFuture(ImmutablePair.of(null, movieFile));
        }

        if (StringUtils.isEmpty(result.get().title())) {
            return CompletableFuture.completedFuture(ImmutablePair.of(null, movieFile));
        }

        Unmarshaller<ByteString, TmdbSearchResults> unmarshal = Jackson.byteStringUnmarshaller(TmdbSearchResults.class);
        JsonEntityStreamingSupport support = EntityStreamingSupport.json(Int.MaxValue());

        Query query;
        if (StringUtils.isEmpty(result.get().year())) {
            query = Query.create(Pair.create("query", result.get().title()), Pair.create("api_key", tmdbApiKey));
        } else {
            query = Query.create(Pair.create("query", result.get().title()), Pair.create("year", result.get().year()), Pair.create("api_key", tmdbApiKey));
        }

        var uri = Uri.create("https://api.themoviedb.org/3/search/movie").query(query);

        return Http.get(actorSystem)
            .singleRequest(HttpRequest.GET(uri.toString()))
            .thenCompose(response ->
                response.entity().getDataBytes()
                    .via(support.framingDecoder())
                    .mapAsync(1, bytes -> unmarshal.unmarshal(bytes, actorSystem))
                    .runReduce((a, b) -> a, actorSystem)
                    .thenApply((a) -> ImmutablePair.of(a, movieFile))
            );
    }

    private Future<ImmutablePair<TmdbSearchResults, MediaFile>> searchForMovieVertx(MediaFile movieFile) {
        var result = videoFilenameExtractionService.extract(movieFile.getFilename());

        if (result.isEmpty()) {
            return Future.succeededFuture(ImmutablePair.of(null, movieFile));
        }

        if (StringUtils.isEmpty(result.get().title())) {
            return Future.succeededFuture(ImmutablePair.of(null, movieFile));
        }

        return theMovieDatabaseService.searchForMovie(result.get()).compose(res -> Future.succeededFuture(ImmutablePair.of(res.body(), movieFile)));
    }

    private MediaFile probeEpisode() {
        return null;
        // TODO: implement
    }

    private String getExtension(File file) {
        return FilenameUtils.getExtension(file.getName());
    }

    private void deleteMissingMediaFiles() {
        // get all items in library
        // locate files in FS
        // cleanup if file cannot be located.
    }
}
