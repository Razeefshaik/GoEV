package com.goev.graph;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LineRepository extends JpaRepository<Line, Integer> {

    Optional<Line> findByReBus(Integer reBus);
}
