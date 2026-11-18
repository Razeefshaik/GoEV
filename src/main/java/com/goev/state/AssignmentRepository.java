package com.goev.state;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {

    List<Assignment> findByEvcsId(String evcsId);
    List<Assignment> findByRequestId(UUID requestId);
    List<Assignment> findByEvId(String evId);
    List<Assignment> findByCommandPendingTrue();
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from Assignment a where a.assignmentId = :id")
    java.util.Optional<Assignment> lockById(@org.springframework.data.repository.query.Param("id") UUID id);
}
