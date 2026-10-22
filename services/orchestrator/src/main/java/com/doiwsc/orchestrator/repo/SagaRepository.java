package com.doiwsc.orchestrator.repo;

import com.doiwsc.orchestrator.domain.Saga;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaRepository extends JpaRepository<Saga, String> {
}
