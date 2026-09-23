package com.streamarr.server.fixtures;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.ImageProperties;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.fakes.FakeTmdbHttpService;
import com.streamarr.server.repositories.media.ImageRepository;
import com.streamarr.server.services.ArtworkFetcher;
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

  /** Unset collaborators default to an empty image repository and a downloader with no image. */
  @Builder(builderMethodName = "artworkServiceBuilder")
  private static ArtworkService artworkService(
      ImageRepository imageRepository,
      TmdbImageDownloader imageDownloader,
      Clock clock,
      FileSystem fileSystem) {
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

    var imageService =
        new ImageService(
            repository,
            new ImageVariantService(),
            new ImageProperties("/data/images"),
            imageFileSystem);
    return new ArtworkService(
        new ArtworkFetcher(downloader, imageService, new MutexFactoryProvider()), artworkClock);
  }
}
