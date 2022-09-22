/*
 * This file is generated by jOOQ.
 */
package com.streamarr.server.jooq.generated;


import com.streamarr.server.jooq.generated.tables.BaseCollectable;
import com.streamarr.server.jooq.generated.tables.Company;
import com.streamarr.server.jooq.generated.tables.Library;
import com.streamarr.server.jooq.generated.tables.MediaFile;
import com.streamarr.server.jooq.generated.tables.Movie;
import com.streamarr.server.jooq.generated.tables.MovieCompany;
import com.streamarr.server.jooq.generated.tables.MoviePerson;
import com.streamarr.server.jooq.generated.tables.Person;
import com.streamarr.server.jooq.generated.tables.Rating;
import com.streamarr.server.jooq.generated.tables.Review;
import com.streamarr.server.jooq.generated.tables.SchemaHistory;
import com.streamarr.server.jooq.generated.tables.Series;
import com.streamarr.server.jooq.generated.tables.records.BaseCollectableRecord;
import com.streamarr.server.jooq.generated.tables.records.CompanyRecord;
import com.streamarr.server.jooq.generated.tables.records.LibraryRecord;
import com.streamarr.server.jooq.generated.tables.records.MediaFileRecord;
import com.streamarr.server.jooq.generated.tables.records.MovieCompanyRecord;
import com.streamarr.server.jooq.generated.tables.records.MoviePersonRecord;
import com.streamarr.server.jooq.generated.tables.records.MovieRecord;
import com.streamarr.server.jooq.generated.tables.records.PersonRecord;
import com.streamarr.server.jooq.generated.tables.records.RatingRecord;
import com.streamarr.server.jooq.generated.tables.records.ReviewRecord;
import com.streamarr.server.jooq.generated.tables.records.SchemaHistoryRecord;
import com.streamarr.server.jooq.generated.tables.records.SeriesRecord;

import org.jooq.ForeignKey;
import org.jooq.TableField;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.Internal;


/**
 * A class modelling foreign key relationships and constraints of tables in
 * public.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class Keys {

    // -------------------------------------------------------------------------
    // UNIQUE and PRIMARY KEY definitions
    // -------------------------------------------------------------------------

    public static final UniqueKey<BaseCollectableRecord> BASE_COLLECTABLE_PKEY = Internal.createUniqueKey(BaseCollectable.BASE_COLLECTABLE, DSL.name("base_collectable_pkey"), new TableField[] { BaseCollectable.BASE_COLLECTABLE.ID }, true);
    public static final UniqueKey<CompanyRecord> COMPANY_PKEY = Internal.createUniqueKey(Company.COMPANY, DSL.name("company_pkey"), new TableField[] { Company.COMPANY.ID }, true);
    public static final UniqueKey<LibraryRecord> LIBRARY_PKEY = Internal.createUniqueKey(Library.LIBRARY, DSL.name("library_pkey"), new TableField[] { Library.LIBRARY.ID }, true);
    public static final UniqueKey<MediaFileRecord> MOVIE_FILE_PKEY = Internal.createUniqueKey(MediaFile.MEDIA_FILE, DSL.name("movie_file_pkey"), new TableField[] { MediaFile.MEDIA_FILE.ID }, true);
    public static final UniqueKey<MovieRecord> MOVIE_PKEY = Internal.createUniqueKey(Movie.MOVIE, DSL.name("movie_pkey"), new TableField[] { Movie.MOVIE.ID }, true);
    public static final UniqueKey<MovieCompanyRecord> MOVIE_COMPANY_PKEY = Internal.createUniqueKey(MovieCompany.MOVIE_COMPANY, DSL.name("movie_company_pkey"), new TableField[] { MovieCompany.MOVIE_COMPANY.ID }, true);
    public static final UniqueKey<MoviePersonRecord> MOVIE_PERSON_PKEY = Internal.createUniqueKey(MoviePerson.MOVIE_PERSON, DSL.name("movie_person_pkey"), new TableField[] { MoviePerson.MOVIE_PERSON.ID }, true);
    public static final UniqueKey<PersonRecord> PERSON_PKEY = Internal.createUniqueKey(Person.PERSON, DSL.name("person_pkey"), new TableField[] { Person.PERSON.ID }, true);
    public static final UniqueKey<RatingRecord> RATING_PKEY = Internal.createUniqueKey(Rating.RATING, DSL.name("rating_pkey"), new TableField[] { Rating.RATING.ID }, true);
    public static final UniqueKey<ReviewRecord> REVIEW_PKEY = Internal.createUniqueKey(Review.REVIEW, DSL.name("review_pkey"), new TableField[] { Review.REVIEW.ID }, true);
    public static final UniqueKey<SchemaHistoryRecord> SCHEMA_HISTORY_PK = Internal.createUniqueKey(SchemaHistory.SCHEMA_HISTORY, DSL.name("schema_history_pk"), new TableField[] { SchemaHistory.SCHEMA_HISTORY.INSTALLED_RANK }, true);
    public static final UniqueKey<SeriesRecord> SERIES_PKEY = Internal.createUniqueKey(Series.SERIES, DSL.name("series_pkey"), new TableField[] { Series.SERIES.ID }, true);

    // -------------------------------------------------------------------------
    // FOREIGN KEY definitions
    // -------------------------------------------------------------------------

    public static final ForeignKey<BaseCollectableRecord, LibraryRecord> BASE_COLLECTABLE__FK_LIBRARY = Internal.createForeignKey(BaseCollectable.BASE_COLLECTABLE, DSL.name("fk_library"), new TableField[] { BaseCollectable.BASE_COLLECTABLE.LIBRARY_ID }, Keys.LIBRARY_PKEY, new TableField[] { Library.LIBRARY.ID }, true);
    public static final ForeignKey<MediaFileRecord, BaseCollectableRecord> MEDIA_FILE__FK_BASE_COLLECTABLE = Internal.createForeignKey(MediaFile.MEDIA_FILE, DSL.name("fk_base_collectable"), new TableField[] { MediaFile.MEDIA_FILE.MEDIA_ID }, Keys.BASE_COLLECTABLE_PKEY, new TableField[] { BaseCollectable.BASE_COLLECTABLE.ID }, true);
    public static final ForeignKey<MediaFileRecord, LibraryRecord> MEDIA_FILE__FK_LIBRARY = Internal.createForeignKey(MediaFile.MEDIA_FILE, DSL.name("fk_library"), new TableField[] { MediaFile.MEDIA_FILE.LIBRARY_ID }, Keys.LIBRARY_PKEY, new TableField[] { Library.LIBRARY.ID }, true);
    public static final ForeignKey<MovieRecord, BaseCollectableRecord> MOVIE__FK_MOVIE = Internal.createForeignKey(Movie.MOVIE, DSL.name("fk_movie"), new TableField[] { Movie.MOVIE.ID }, Keys.BASE_COLLECTABLE_PKEY, new TableField[] { BaseCollectable.BASE_COLLECTABLE.ID }, true);
    public static final ForeignKey<MovieCompanyRecord, CompanyRecord> MOVIE_COMPANY__MOVIE_COMPANY_COMPANY_ID_FKEY = Internal.createForeignKey(MovieCompany.MOVIE_COMPANY, DSL.name("movie_company_company_id_fkey"), new TableField[] { MovieCompany.MOVIE_COMPANY.COMPANY_ID }, Keys.COMPANY_PKEY, new TableField[] { Company.COMPANY.ID }, true);
    public static final ForeignKey<MovieCompanyRecord, MovieRecord> MOVIE_COMPANY__MOVIE_COMPANY_MOVIE_ID_FKEY = Internal.createForeignKey(MovieCompany.MOVIE_COMPANY, DSL.name("movie_company_movie_id_fkey"), new TableField[] { MovieCompany.MOVIE_COMPANY.MOVIE_ID }, Keys.MOVIE_PKEY, new TableField[] { Movie.MOVIE.ID }, true);
    public static final ForeignKey<MoviePersonRecord, MovieRecord> MOVIE_PERSON__MOVIE_PERSON_MOVIE_ID_FKEY = Internal.createForeignKey(MoviePerson.MOVIE_PERSON, DSL.name("movie_person_movie_id_fkey"), new TableField[] { MoviePerson.MOVIE_PERSON.MOVIE_ID }, Keys.MOVIE_PKEY, new TableField[] { Movie.MOVIE.ID }, true);
    public static final ForeignKey<MoviePersonRecord, PersonRecord> MOVIE_PERSON__MOVIE_PERSON_PERSON_ID_FKEY = Internal.createForeignKey(MoviePerson.MOVIE_PERSON, DSL.name("movie_person_person_id_fkey"), new TableField[] { MoviePerson.MOVIE_PERSON.PERSON_ID }, Keys.PERSON_PKEY, new TableField[] { Person.PERSON.ID }, true);
    public static final ForeignKey<RatingRecord, MovieRecord> RATING__FK_MOVIE = Internal.createForeignKey(Rating.RATING, DSL.name("fk_movie"), new TableField[] { Rating.RATING.MOVIE_ID }, Keys.MOVIE_PKEY, new TableField[] { Movie.MOVIE.ID }, true);
    public static final ForeignKey<ReviewRecord, MovieRecord> REVIEW__FK_MOVIE = Internal.createForeignKey(Review.REVIEW, DSL.name("fk_movie"), new TableField[] { Review.REVIEW.MOVIE_ID }, Keys.MOVIE_PKEY, new TableField[] { Movie.MOVIE.ID }, true);
    public static final ForeignKey<SeriesRecord, BaseCollectableRecord> SERIES__FK_SERIES = Internal.createForeignKey(Series.SERIES, DSL.name("fk_series"), new TableField[] { Series.SERIES.ID }, Keys.BASE_COLLECTABLE_PKEY, new TableField[] { BaseCollectable.BASE_COLLECTABLE.ID }, true);
}