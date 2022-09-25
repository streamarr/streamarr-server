package com.streamarr.server.services.library;

import com.github.mizosoft.methanol.Methanol;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.external.tmdb.TmdbSearchResults;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.metadata.HttpClientTheMovieDatabaseService;
import com.streamarr.server.services.parsers.video.DefaultVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.VideoFileMetadata;
import com.streamarr.server.services.validation.VideoExtensionValidator;
import io.vertx.core.Vertx;
import io.vertx.core.shareddata.SharedData;
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
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;


@Service
@RequiredArgsConstructor
public class LoomLibraryManagementService implements InitializingBean {

    private final VideoExtensionValidator videoExtensionValidator;
    private final DefaultVideoFileMetadataParser defaultVideoFileMetadataParser;
    private final HttpClientTheMovieDatabaseService theMovieDatabaseService;
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

        log.info("Starting " + library.getName() + " library scan.");
        var startTime = Instant.now();

        library.setStatus(LibraryStatus.SCANNING);
        library.setRefreshStartedOn(startTime);
        libraryRepository.save(library);

        try (var httpClientExecutorService = Executors.newVirtualThreadPerTaskExecutor();
             var executor = Executors.newVirtualThreadPerTaskExecutor();
             var stream = Files.walk(Paths.get(library.getFilepath()))) {

            HttpClient client = Methanol.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(httpClientExecutorService)
                .build();

            stream
                .filter(Files::isRegularFile)
                .map(Path::toFile)
                .forEach(file -> {
                    executor.submit(() -> processFile(library, file, client));
                });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.info("Scanned MediaFiles from Library at filepath: {}", library.getFilepath());
    }

    private void processFile(Library library, File file, HttpClient client) {

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

        log.info("Parsed filename. Title: {} and Year: {}", mediaInformationResult.get().title(), mediaInformationResult.get().year());

        try {
            var searchResult = searchForMedia(mediaInformationResult.get(), client);
            var firstResult = searchResult.body().getResults().get(0);

            enrichMediaMetadata(library, mediaFile, firstResult.getId(), client);
        } catch (Exception ex) {
            log.error("Failure finding search results:", ex);
        }
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
        // TODO: implement
        return null;
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

    private <T> HttpResponse<TmdbSearchResults> searchForMedia(T information, HttpClient client) throws IOException, InterruptedException {
        return switch (information) {
            case VideoFileMetadata videoFileMetadata ->
                theMovieDatabaseService.searchForMovie(videoFileMetadata, client);
            default -> throw new IllegalStateException("Unexpected value: " + information);
        };
    }

    private void enrichMediaMetadata(Library library, MediaFile mediaFile, int id, HttpClient client) {
        switch (library.getType()) {
            case MOVIE -> enrichMovieMetadata(library, mediaFile, String.valueOf(id), client);
            default -> throw new IllegalStateException("Unexpected value: " + mediaFile);
        }
        ;
    }

    private void enrichMovieMetadata(Library library, MediaFile mediaFile, String id, HttpClient client) {
        try {
            var movieResult = theMovieDatabaseService.getMovieMetadata(String.valueOf(id), client);

            log.info(movieResult.body().getTitle());
        } catch (Exception ex) {
            log.error("Failure getting movie metadata:", ex);
        }
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
