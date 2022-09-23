package com.streamarr.server.services.validation;


import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class VideoExtensionValidator {

    private final Set<String> supportedExtensions = Set.of(
        "m4v",
        "mov",
        "wmv",
        "mpg",
        "mpeg",
        "mp4",
        "mkv",
        "avi",
        "webm",
        "m2ts",
        "flv"
    );

    public boolean validate(String fileExtension) {
        return supportedExtensions.stream().anyMatch(fileExtension::equalsIgnoreCase);
    }
}
