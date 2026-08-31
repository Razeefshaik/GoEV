package com.goev.evcs;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EvcsRepository extends JpaRepository<Evcs, String> {

    List<Evcs> findByBusId(Integer busId);
}
