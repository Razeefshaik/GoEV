package com.goev.state;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChargeRequestRepository extends JpaRepository<ChargeRequest, UUID> {
}
