package com.poc.aiassistant.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.poc.aiassistant.entity.TaskDuplicateMatch;
import com.poc.aiassistant.entity.TaskDuplicateMatchType;

public interface TaskDuplicateMatchRepository
        extends JpaRepository<TaskDuplicateMatch, UUID> {

    /**
     * The human review queue for AMBIGUOUS semantic matches: every
     * new task that was created despite an unresolved possible
     * duplicate. There is no dedicated UI/endpoint for this yet
     * (see limitations) — this query is what a future one would use.
     */
    List<TaskDuplicateMatch> findByMatchType(TaskDuplicateMatchType matchType);

    List<TaskDuplicateMatch> findByMatchedTaskId(UUID matchedTaskId);
}
