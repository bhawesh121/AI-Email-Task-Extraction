package com.poc.aiassistant.entity;

public enum TaskStatus {

    NEW,

    IN_PROGRESS,

    COMPLETED,

    BLOCKED,

    /**
     * Terminal lifecycle state. An ARCHIVED task has left the active
     * workload and no longer participates in duplicate detection:
     * a later, semantically-equivalent task from the same sender is
     * allowed to be created as a brand new logical task.
     *
     * ARCHIVED tasks are kept (not deleted) for audit/history.
     */
    ARCHIVED
}