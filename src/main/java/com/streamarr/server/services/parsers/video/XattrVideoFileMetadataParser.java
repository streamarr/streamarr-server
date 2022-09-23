package com.streamarr.server.services.parsers.video;

import com.streamarr.server.services.parsers.MetadataParser;

import java.util.Optional;

public class XattrVideoFileMetadataParser implements MetadataParser<VideoFileMetadata> {

    @Override
    public Optional<VideoFileMetadata> parse(String filename) {
        // TODO: Implement, read file attributes to get data. Example: Filebot xattr ... https://github.com/IIeTp/Filebot-Xattr-Scanners-ID
        return Optional.empty();
    }
}
