/*
 * This file is generated by jOOQ.
 */
package com.streamarr.server.jooq.generated.tables;


import com.streamarr.server.jooq.generated.Indexes;
import com.streamarr.server.jooq.generated.Keys;
import com.streamarr.server.jooq.generated.Public;
import com.streamarr.server.jooq.generated.enums.MediaFileStatus;
import com.streamarr.server.jooq.generated.tables.records.MediaFileRecord;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Function11;
import org.jooq.Index;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Records;
import org.jooq.Row11;
import org.jooq.Schema;
import org.jooq.SelectField;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableOptions;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class MediaFile extends TableImpl<MediaFileRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>public.media_file</code>
     */
    public static final MediaFile MEDIA_FILE = new MediaFile();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<MediaFileRecord> getRecordType() {
        return MediaFileRecord.class;
    }

    /**
     * The column <code>public.media_file.id</code>.
     */
    public final TableField<MediaFileRecord, UUID> ID = createField(DSL.name("id"), SQLDataType.UUID.nullable(false).defaultValue(DSL.field(DSL.raw("uuid_generate_v4()"), SQLDataType.UUID)), this, "");

    /**
     * The column <code>public.media_file.created_on</code>.
     */
    public final TableField<MediaFileRecord, OffsetDateTime> CREATED_ON = createField(DSL.name("created_on"), SQLDataType.TIMESTAMPWITHTIMEZONE(6).nullable(false).defaultValue(DSL.field(DSL.raw("now()"), SQLDataType.TIMESTAMPWITHTIMEZONE)), this, "");

    /**
     * The column <code>public.media_file.created_by</code>.
     */
    public final TableField<MediaFileRecord, UUID> CREATED_BY = createField(DSL.name("created_by"), SQLDataType.UUID.nullable(false), this, "");

    /**
     * The column <code>public.media_file.last_modified_on</code>.
     */
    public final TableField<MediaFileRecord, OffsetDateTime> LAST_MODIFIED_ON = createField(DSL.name("last_modified_on"), SQLDataType.TIMESTAMPWITHTIMEZONE(6).nullable(false).defaultValue(DSL.field(DSL.raw("now()"), SQLDataType.TIMESTAMPWITHTIMEZONE)), this, "");

    /**
     * The column <code>public.media_file.last_modified_by</code>.
     */
    public final TableField<MediaFileRecord, UUID> LAST_MODIFIED_BY = createField(DSL.name("last_modified_by"), SQLDataType.UUID, this, "");

    /**
     * The column <code>public.media_file.filename</code>.
     */
    public final TableField<MediaFileRecord, String> FILENAME = createField(DSL.name("filename"), SQLDataType.CLOB.nullable(false), this, "");

    /**
     * The column <code>public.media_file.filepath</code>.
     */
    public final TableField<MediaFileRecord, String> FILEPATH = createField(DSL.name("filepath"), SQLDataType.CLOB.nullable(false), this, "");

    /**
     * The column <code>public.media_file.size</code>.
     */
    public final TableField<MediaFileRecord, Long> SIZE = createField(DSL.name("size"), SQLDataType.BIGINT.nullable(false), this, "");

    /**
     * The column <code>public.media_file.media_id</code>.
     */
    public final TableField<MediaFileRecord, UUID> MEDIA_ID = createField(DSL.name("media_id"), SQLDataType.UUID, this, "");

    /**
     * The column <code>public.media_file.library_id</code>.
     */
    public final TableField<MediaFileRecord, UUID> LIBRARY_ID = createField(DSL.name("library_id"), SQLDataType.UUID.nullable(false), this, "");

    /**
     * The column <code>public.media_file.status</code>.
     */
    public final TableField<MediaFileRecord, MediaFileStatus> STATUS = createField(DSL.name("status"), SQLDataType.VARCHAR.nullable(false).asEnumDataType(com.streamarr.server.jooq.generated.enums.MediaFileStatus.class), this, "");

    private MediaFile(Name alias, Table<MediaFileRecord> aliased) {
        this(alias, aliased, null);
    }

    private MediaFile(Name alias, Table<MediaFileRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>public.media_file</code> table reference
     */
    public MediaFile(String alias) {
        this(DSL.name(alias), MEDIA_FILE);
    }

    /**
     * Create an aliased <code>public.media_file</code> table reference
     */
    public MediaFile(Name alias) {
        this(alias, MEDIA_FILE);
    }

    /**
     * Create a <code>public.media_file</code> table reference
     */
    public MediaFile() {
        this(DSL.name("media_file"), null);
    }

    public <O extends Record> MediaFile(Table<O> child, ForeignKey<O, MediaFileRecord> key) {
        super(child, key, MEDIA_FILE);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Public.PUBLIC;
    }

    @Override
    public List<Index> getIndexes() {
        return Arrays.asList(Indexes.MEDIA_FILE_FILEPATH_IDX);
    }

    @Override
    public UniqueKey<MediaFileRecord> getPrimaryKey() {
        return Keys.MOVIE_FILE_PKEY;
    }

    @Override
    public List<ForeignKey<MediaFileRecord, ?>> getReferences() {
        return Arrays.asList(Keys.MEDIA_FILE__FK_BASE_COLLECTABLE, Keys.MEDIA_FILE__FK_LIBRARY);
    }

    private transient BaseCollectable _baseCollectable;
    private transient Library _library;

    /**
     * Get the implicit join path to the <code>public.base_collectable</code>
     * table.
     */
    public BaseCollectable baseCollectable() {
        if (_baseCollectable == null)
            _baseCollectable = new BaseCollectable(this, Keys.MEDIA_FILE__FK_BASE_COLLECTABLE);

        return _baseCollectable;
    }

    /**
     * Get the implicit join path to the <code>public.library</code> table.
     */
    public Library library() {
        if (_library == null)
            _library = new Library(this, Keys.MEDIA_FILE__FK_LIBRARY);

        return _library;
    }

    @Override
    public MediaFile as(String alias) {
        return new MediaFile(DSL.name(alias), this);
    }

    @Override
    public MediaFile as(Name alias) {
        return new MediaFile(alias, this);
    }

    @Override
    public MediaFile as(Table<?> alias) {
        return new MediaFile(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public MediaFile rename(String name) {
        return new MediaFile(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public MediaFile rename(Name name) {
        return new MediaFile(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public MediaFile rename(Table<?> name) {
        return new MediaFile(name.getQualifiedName(), null);
    }

    // -------------------------------------------------------------------------
    // Row11 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row11<UUID, OffsetDateTime, UUID, OffsetDateTime, UUID, String, String, Long, UUID, UUID, MediaFileStatus> fieldsRow() {
        return (Row11) super.fieldsRow();
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Function)}.
     */
    public <U> SelectField<U> mapping(Function11<? super UUID, ? super OffsetDateTime, ? super UUID, ? super OffsetDateTime, ? super UUID, ? super String, ? super String, ? super Long, ? super UUID, ? super UUID, ? super MediaFileStatus, ? extends U> from) {
        return convertFrom(Records.mapping(from));
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Class,
     * Function)}.
     */
    public <U> SelectField<U> mapping(Class<U> toType, Function11<? super UUID, ? super OffsetDateTime, ? super UUID, ? super OffsetDateTime, ? super UUID, ? super String, ? super String, ? super Long, ? super UUID, ? super UUID, ? super MediaFileStatus, ? extends U> from) {
        return convertFrom(toType, Records.mapping(from));
    }
}
