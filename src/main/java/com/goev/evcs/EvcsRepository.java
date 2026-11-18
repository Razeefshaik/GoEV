package com.goev.evcs;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EvcsRepository extends JpaRepository<Evcs, String> {

    List<Evcs> findByBusId(Integer busId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from Evcs e where e.evcsId = :id")
    java.util.Optional<Evcs> lockById(@org.springframework.data.repository.query.Param("id") String id);
}
