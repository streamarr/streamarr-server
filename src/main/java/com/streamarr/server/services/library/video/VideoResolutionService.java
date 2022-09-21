package com.streamarr.server.services.library.video;

import com.streamarr.server.services.extraction.video.DefaultVideoFileExtractionService;
import com.streamarr.server.utils.VideoExtensionValidator;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@RequiredArgsConstructor
public class VideoResolutionService {

    private final VideoExtensionValidator videoExtensionValidator;
    private final DefaultVideoFileExtractionService cleanTitleExtractor;

    // TODO: Just playing around with file parsing, TBD.
    public void parsePaths() {

        try {
            Files.walk(Paths.get("/Users/stuckya/Downloads/Test"))
                .filter(Files::isRegularFile)
                .forEach(this::parsePath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void parsePath(Path path) {
        var file = path.toFile();

        var extension = getExtension(file);

        if (!videoExtensionValidator.validate(extension)) {
            throw new RuntimeException("Unsupported movie file type.");
        }

        System.out.println(file.getAbsolutePath());

        var baseName = getBaseName(file);

        var optionalResult = cleanTitleExtractor.extract(baseName);

        if (optionalResult.isEmpty()) {
            throw new RuntimeException("Unable to parse filename.");
        }

        var result = optionalResult.get();

        System.out.println(result.title());
        System.out.println(result.year());
    }

    private String getExtension(File file) {
        return FilenameUtils.getExtension(file.getName());
    }

    private String getBaseName(File file) {
        return FilenameUtils.getBaseName(file.getName());
    }
}
