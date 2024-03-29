/*
 * This file is generated by jOOQ.
 */
package com.streamarr.server.jooq.generated.enums;


import com.streamarr.server.jooq.generated.Public;

import org.jooq.Catalog;
import org.jooq.EnumType;
import org.jooq.Schema;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public enum MediaFileStatus implements EnumType {

    UNMATCHED("UNMATCHED"),

    FILENAME_PARSING_FAILED("FILENAME_PARSING_FAILED"),

    MEDIA_SEARCH_FAILED("MEDIA_SEARCH_FAILED"),

    FAILED_METADATA_ENRICHMENT("FAILED_METADATA_ENRICHMENT"),

    MATCHED("MATCHED");

    private final String literal;

    private MediaFileStatus(String literal) {
        this.literal = literal;
    }

    @Override
    public Catalog getCatalog() {
        return getSchema().getCatalog();
    }

    @Override
    public Schema getSchema() {
        return Public.PUBLIC;
    }

    @Override
    public String getName() {
        return "media_file_status";
    }

    @Override
    public String getLiteral() {
        return literal;
    }

    /**
     * Lookup a value of this EnumType by its literal
     */
    public static MediaFileStatus lookupLiteral(String literal) {
        return EnumType.lookupLiteral(MediaFileStatus.class, literal);
    }
}
