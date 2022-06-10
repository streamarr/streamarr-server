package com.streamarr.server.utils;


import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class VideoExtensionValidator {

    // TODO: Pulled from Jellyfin... Are these correct for us? https://github.com/jellyfin/jellyfin/blob/91d6ffd731e163bf281348872c2421598fa4edb2/Emby.Naming/Common/NamingOptions.cs#L599
    private final Set<String> supportedExtensions = Set.of(
        "m4v",
        "3gp",
        "nsv",
        "ts",
        "ty",
        "strm",
        "rm",
        "rmvb",
        "ifo",
        "mov",
        "qt",
        "divx",
        "xvid",
        "bivx",
        "vob",
        "nrg",
        "img",
        "iso",
        "pva",
        "wmv",
        "asf",
        "asx",
        "ogm",
        "m2v",
        "avi",
        "bin",
        "dvr-ms",
        "mpg",
        "mpeg",
        "mp4",
        "mkv",
        "avc",
        "vp3",
        "svq3",
        "nuv",
        "viv",
        "dv",
        "fli",
        "flv",
        "001",
        "tp"
    );

    public boolean validate(String fileExtension) {
        return supportedExtensions.stream().anyMatch(fileExtension::equalsIgnoreCase);
    }
}
