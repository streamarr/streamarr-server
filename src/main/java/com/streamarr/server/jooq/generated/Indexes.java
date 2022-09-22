/*
 * This file is generated by jOOQ.
 */
package com.streamarr.server.jooq.generated;


import com.streamarr.server.jooq.generated.tables.Library;
import com.streamarr.server.jooq.generated.tables.MediaFile;
import com.streamarr.server.jooq.generated.tables.SchemaHistory;

import org.jooq.Index;
import org.jooq.OrderField;
import org.jooq.impl.DSL;
import org.jooq.impl.Internal;


/**
 * A class modelling indexes of tables in public.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class Indexes {

    // -------------------------------------------------------------------------
    // INDEX definitions
    // -------------------------------------------------------------------------

    public static final Index LIBRARY_FILE_PATH_IDX = Internal.createIndex(DSL.name("library_file_path_idx"), Library.LIBRARY, new OrderField[] { Library.LIBRARY.FILEPATH }, true);
    public static final Index MOVIE_FILE_FILEPATH_IDX = Internal.createIndex(DSL.name("movie_file_filepath_idx"), MediaFile.MEDIA_FILE, new OrderField[] { MediaFile.MEDIA_FILE.FILEPATH }, true);
    public static final Index SCHEMA_HISTORY_S_IDX = Internal.createIndex(DSL.name("schema_history_s_idx"), SchemaHistory.SCHEMA_HISTORY, new OrderField[] { SchemaHistory.SCHEMA_HISTORY.SUCCESS }, false);
}