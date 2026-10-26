package com.importer.fileimporter.service;

import com.importer.fileimporter.entity.SyncEntityType;

/** Thrown when a full-sync is requested while a job of the same entity type is already PENDING/RUNNING. */
public class SyncJobAlreadyRunningException extends RuntimeException {
    public SyncJobAlreadyRunningException(SyncEntityType entityType) {
        super("A " + entityType + " sync job is already in progress for this exchange config");
    }
}
