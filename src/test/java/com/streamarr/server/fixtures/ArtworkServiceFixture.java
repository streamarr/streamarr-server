package com.streamarr.server.fixtures;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.ImageProperties;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.fakes.FakeItemResultRepository;
import com.streamarr.server.fakes.FakeTmdbHttpService;
import com.streamarr.server.repositories.media.ImageRepository;
import com.streamarr.server.repositories.media.ItemResultRepository;
import com.streamarr.server.services.ArtworkFetcher;
import com.streamarr.server.services.ArtworkProgress;
import com.streamarr.server.services.ArtworkService;
import com.streamarr.server.services.ImageService;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.ImageVariantService;
import com.streamarr.server.services.metadata.TmdbImageDownloader;
import java.nio.file.FileSystem;
import java.time.Clock;
import lombok.Builder;

public final class ArtworkServiceFixture {

  private ArtworkServiceFixture() {}

  /**
   * Unset collaborators default to empty image and result repositories and a downloader with no
   * image.
   */
  @Builder(builderMethodName = "artworkServiceBuilder")
  private static ArtworkService artworkService(
      ImageRepository imageRepository,
      TmdbImageDownloader imageDownloader,
      Clock clock,
      FileSystem fileSystem,
      Integer secondaryConcurrency,
      ArtworkProgress progress,
      ItemResultRepository itemResults) {
    var repository = imageRepository;
    if (repository == null) {
      repository = new FakeImageRepository();
    }

    var downloader = imageDownloader;
    if (downloader == null) {
      downloader = new FakeTmdbHttpService();
    }

    var artworkClock = clock;
    if (artworkClock == null) {
      artworkClock = Clock.systemUTC();
    }

    var imageFileSystem = fileSystem;
    if (imageFileSystem == null) {
      imageFileSystem = Jimfs.newFileSystem(Configuration.unix());
    }

    var concurrency = secondaryConcurrency;
    if (concurrency == null) {
      concurrency = 4;
    }

    var artworkProgress = progress;
    if (artworkProgress == null) {
      artworkProgress = new ArtworkProgress(artworkClock);
    }

    var results = itemResults;
    if (results == null) {
      results = new FakeItemResultRepository();
    }

    var imageService =
        new ImageService(
            repository,
            new ImageVariantService(),
            new ImageProperties("/data/images"),
            imageFileSystem,
            results);
    return new ArtworkService(
        new ArtworkFetcher(downloader, imageService, results, new MutexFactoryProvider()),
        artworkProgress,
        artworkClock,
        concurrency);
  }
}
