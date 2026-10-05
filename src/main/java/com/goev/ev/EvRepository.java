package com.goev.ev;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EvRepository extends JpaRepository<Ev, String> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from Ev e where e.evId = :id")
    java.util.Optional<Ev> lockById(@org.springframework.data.repository.query.Param("id") String id);
}
