package com.streamarr.server.services.parsers.video;

import com.streamarr.server.services.parsers.MetadataParser;

import java.util.Optional;

public class XattrVideoFileMetadataParser implements MetadataParser<VideoFileMetadata> {

    @Override
    public Optional<VideoFileMetadata> extract(String filename) {
        // TODO: Read file attributes to get data
        // TODO: Filebot xattr ... https://github.com/IIeTp/Filebot-Xattr-Scanners-ID
        return Optional.empty();
    }
}
