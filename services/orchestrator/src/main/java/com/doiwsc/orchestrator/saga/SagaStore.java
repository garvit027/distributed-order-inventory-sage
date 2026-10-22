package com.doiwsc.orchestrator.saga;

import com.doiwsc.orchestrator.domain.Saga;
import com.doiwsc.orchestrator.domain.SagaItem;
import com.doiwsc.orchestrator.repo.SagaRepository;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Transactional find-or-create for a saga. Returns false when the saga already
 * exists (idempotent StartSaga), true when this call created it. The primary key
 * on saga_id makes concurrent duplicate Starts safe: the loser surfaces a
 * constraint violation and is treated as a duplicate.
 */
@Component
public class SagaStore {

    private final SagaRepository repository;

    public SagaStore(SagaRepository repository) {
        this.repository = repository;
    }

    public boolean createIfAbsent(String sagaId, String orderId, String customerId,
                                  String recipient, long amountCents, String currency,
                                  List<SagaItem> items) {
        if (repository.existsById(sagaId)) {
            return false;
        }
        Saga saga = new Saga(sagaId, orderId, customerId, recipient, amountCents, currency, items);
        try {
            repository.saveAndFlush(saga);
            return true;
        } catch (DataIntegrityViolationException race) {
            return false;
        }
    }
}
