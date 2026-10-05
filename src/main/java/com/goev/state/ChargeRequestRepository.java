package com.goev.state;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChargeRequestRepository extends JpaRepository<ChargeRequest, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from ChargeRequest r where r.requestId = :id")
    java.util.Optional<ChargeRequest> lockById(@org.springframework.data.repository.query.Param("id") UUID id);
}
