package com.streamarr.server.services.library;

// TODO: Implement, inspiration here https://gitlab.com/olaris/olaris-server/-/blob/develop/metadata/managers/library.go
public class LibraryManagementService {

    public void addLibrary() {
        // validate
        // save new "Library" entity
        // refreshAll
        // register watcher
    }

    public void removeLibrary() {
        // remove watcher
        // cleanup?
        // terminate streams?
        // remove all db entries but leave files (shows, movies, etc)
        // delete "Library" entity
    }

    private void refreshAll() {
        // DEFINITION: "media file" an entity that describes the file and
        // serves as an intermediate step until we can resolve metadata.

        // deleteMissing() - check all files in the database to ensure they still exist
        // ensure we can access library filepath
        // walk path from root
        // validate file
        // ensure "media file" isn't already in DB (Instead rely on DB constraint?)
        // "probe file"
        // save "media file"; series, season, episode, movie, song, etc.
        // get metadata using "media file".
    }

    private void deleteMissing() {
        // get all items in library
        // locate files in FS
        // cleanup if file cannot be located.
    }
}
