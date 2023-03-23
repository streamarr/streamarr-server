package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderFactory;
import com.streamarr.server.services.parsers.video.DefaultVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import com.streamarr.server.services.validation.VideoExtensionValidator;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;


@Service
public class LibraryManagementService {

    private final VideoExtensionValidator videoExtensionValidator;
    private final DefaultVideoFileMetadataParser defaultVideoFileMetadataParser;
    private final MovieMetadataProviderFactory movieMetadataProviderFactory;
    private final LibraryRepository libraryRepository;
    private final MediaFileRepository mediaFileRepository;
    private final MovieService movieService;
    private final Logger log;
    private final FileSystem fileSystem;
    private final MutexFactory<String> mutexFactory;

    public LibraryManagementService(
        VideoExtensionValidator videoExtensionValidator,
        DefaultVideoFileMetadataParser defaultVideoFileMetadataParser,
        MovieMetadataProviderFactory movieMetadataProviderFactory,
        LibraryRepository libraryRepository,
        MediaFileRepository mediaFileRepository,
        MovieService movieService,
        Logger log,
        MutexFactoryProvider mutexFactoryProvider,
        FileSystem fileSystem
    ) {
        this.videoExtensionValidator = videoExtensionValidator;
        this.defaultVideoFileMetadataParser = defaultVideoFileMetadataParser;
        this.movieMetadataProviderFactory = movieMetadataProviderFactory;
        this.libraryRepository = libraryRepository;
        this.mediaFileRepository = mediaFileRepository;
        this.movieService = movieService;
        this.log = log;
        this.fileSystem = fileSystem;

        this.mutexFactory = mutexFactoryProvider.getMutexFactory();
    }

    public void addLibrary(Library library) {
        // validate library doesn't already exist.
        // save new library entity to database.
        // call refreshLibrary() once new library has been created.
        // return newly created library.
    }

    public void removeLibrary() {
        // remove watcher
        // cleanup?
        // terminate streams?
        // remove all db entries but leave files (shows, movies, etc)
        // delete "Library" entity
    }

    // TODO: Should this be it's own class, LibraryRefreshService?
    // TODO: Should 'Scanning' be separated from 'Refreshing'?
    // TODO: Scan == Just checking files for changes, ie. added, deleted, renamed, moved (could be more efficient)
    // TODO: Refresh == Scan + Metadata
    public void refreshLibrary(UUID libraryId) {
        var optionalLibrary = libraryRepository.findById(libraryId);

        if (optionalLibrary.isEmpty()) {
            throw new RuntimeException("Library cannot be found for refresh.");
        }

        var library = optionalLibrary.get();

        log.info("Starting {} library scan.", library.getName());

        var startTime = Instant.now();

        library.setStatus(LibraryStatus.SCANNING);
        library.setRefreshStartedOn(startTime);
        libraryRepository.save(library);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var stream = Files.walk(fileSystem.getPath(library.getFilepath()))) {

            stream
                .filter(Files::isRegularFile)
                .forEach(file -> executor.submit(() -> processFile(library, file)));

        } catch (IOException e) {
            var endTimeOfFailure = Instant.now();

            library.setStatus(LibraryStatus.UNHEALTHY);
            library.setRefreshCompletedOn(endTimeOfFailure);
            libraryRepository.save(library);

            log.error("Failed to access {} library during scan attempt.", library.getName(), e);

            return;
        }

        var endTime = Instant.now();
        var elapsedTime = Duration.between(startTime, endTime).getSeconds();

        library.setStatus(LibraryStatus.HEALTHY);
        library.setRefreshCompletedOn(endTime);
        libraryRepository.save(library);

        log.info("Finished {} library scan in {} seconds.", library.getName(), elapsedTime);
    }

    private void processFile(Library library, Path path) {

        if (isUnsupportedFileExtension(path)) {
            return;
        }

        var mediaFile = probeFile(library, path);

        if (isAlreadyMatched(mediaFile)) {
            return;
        }

        switch (library.getType()) {
            case MOVIE -> processMovieFileType(library, mediaFile);
            case SERIES -> throw new UnsupportedOperationException("Not implemented yet");
        }
    }

    private boolean isUnsupportedFileExtension(Path path) {
        var extension = getExtension(path);

        return !videoExtensionValidator.validate(extension);
    }

    private String getExtension(Path path) {
        return FilenameUtils.getExtension(path.getFileName().toString());
    }

    private MediaFile probeFile(Library library, Path path) {
        var absoluteFilepath = path.toAbsolutePath().toString();

        var optionalMediaFile = mediaFileRepository.findFirstByFilepath(absoluteFilepath);

        if (optionalMediaFile.isPresent()) {
            log.info("MediaFile id: '{}' already exists, not adding again.", optionalMediaFile.get().getId());
            return optionalMediaFile.get();
        }

        long fileSize = 0;
        try {
            fileSize = Files.size(path);
        } catch (IOException ex) {
            // TODO: What about SecurityException?
            log.error("Could not get filesize at path: {} media might be corrupt.", absoluteFilepath, ex);
        }

        return mediaFileRepository.save(MediaFile.builder()
            .status(MediaFileStatus.UNMATCHED)
            .filename(path.getFileName().toString())
            .filepath(absoluteFilepath)
            .size(fileSize)
            .libraryId(library.getId())
            .build());
    }

    private boolean isAlreadyMatched(MediaFile file) {
        return file.getStatus().equals(MediaFileStatus.MATCHED);
    }

    private void processMovieFileType(Library library, MediaFile mediaFile) {
        var mediaInformationResult = parseMediaFileForMovieInfo(mediaFile);

        if (mediaInformationResult.isEmpty()) {
            mediaFile.setStatus(MediaFileStatus.METADATA_PARSING_FAILED);
            mediaFileRepository.save(mediaFile);

            log.error("Failed to parse file at path '{}'", mediaFile.getFilepath());

            return;
        }

        log.info("Parsed filename. Title: {} and Year: {}", mediaInformationResult.get().title(), mediaInformationResult.get().year());

        var movieSearchResult = movieMetadataProviderFactory.search(library, mediaInformationResult.get());

        if (movieSearchResult.isEmpty()) {
            mediaFile.setStatus(MediaFileStatus.SEARCH_FAILED);
            mediaFileRepository.save(mediaFile);

            log.error("Failed to find matching search result for file at path '{}'", mediaFile.getFilepath());

            return;
        }

        enrichMovieMetadata(library, mediaFile, movieSearchResult.get());
    }

    private Optional<VideoFileParserResult> parseMediaFileForMovieInfo(MediaFile mediaFile) {
        var result = defaultVideoFileMetadataParser.parse(mediaFile.getFilename());

        if (result.isEmpty() || StringUtils.isEmpty(result.get().title())) {
            return Optional.empty();
        }

        return result;
    }

    // TODO: Naming, this does more than enriching, it also saves...
    private void enrichMovieMetadata(Library library, MediaFile mediaFile, RemoteSearchResult remoteSearchResult) {

        // Lock should use the library agent's external id.
        var mutex = mutexFactory.getMutex(remoteSearchResult.externalId());

        try {
            mutex.lock();

            updateOrSaveEnrichedMovie(library, mediaFile, remoteSearchResult);
        } catch (Exception ex) {
            log.error("Failure enriching movie metadata:", ex);
        } finally {
            if (mutex.isHeldByCurrentThread()) {
                mutex.unlock();
            }
        }
    }

    // TODO: Naming...
    private void updateOrSaveEnrichedMovie(Library library, MediaFile mediaFile, RemoteSearchResult remoteSearchResult) {
        var optionalMovie = movieService.addMediaFileToMovieByTmdbId(remoteSearchResult.externalId(), mediaFile);

        // TODO: VERIFY ASSUMPTION! This means movie metadata can never be updated via this process. Is this okay?
        // If we have a movie in the DB, no need to fetch metadata again.
        if (optionalMovie.isPresent()) {
            return;
        }

        var movieToSave = movieMetadataProviderFactory.getMetadata(remoteSearchResult, library);

        if (movieToSave.isEmpty()) {
            return;
        }

        var cast = movieToSave.get().getCast();

        // TODO: VERIFY ASSUMPTION! This means people wont be updated in this process.
        // Get or create cast before creating movie. This prevents duplicates.
        // TODO: TBD; Where should we actually compose the persistence of Movie and it's children.
        var savedCast = movieService.getOrCreateCast(cast);

        movieService.saveMovieWithMediaFileAndCast(movieToSave.get(), mediaFile, savedCast);
    }

    private void deleteMissingMediaFiles() {
        // get all items in library
        // locate files in FS
        // cleanup if file cannot be located.
    }

}
