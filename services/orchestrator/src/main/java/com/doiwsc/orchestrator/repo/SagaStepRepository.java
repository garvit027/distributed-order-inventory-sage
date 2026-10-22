package com.doiwsc.orchestrator.repo;

import com.doiwsc.orchestrator.domain.SagaStep;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaStepRepository extends JpaRepository<SagaStep, Long> {

    List<SagaStep> findBySagaIdOrderByIdAsc(String sagaId);
}
