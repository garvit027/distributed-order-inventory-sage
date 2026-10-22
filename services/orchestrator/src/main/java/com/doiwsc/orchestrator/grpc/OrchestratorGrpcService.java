package com.doiwsc.orchestrator.grpc;

import com.doiwsc.orchestrator.domain.Saga;
import com.doiwsc.orchestrator.domain.SagaStep;
import com.doiwsc.orchestrator.gateway.StockLine;
import com.doiwsc.orchestrator.repo.SagaRepository;
import com.doiwsc.orchestrator.repo.SagaStepRepository;
import com.doiwsc.orchestrator.saga.SagaStarter;
import com.doiwsc.orchestrator.v1.GetSagaRequest;
import com.doiwsc.orchestrator.v1.GetSagaResponse;
import com.doiwsc.orchestrator.v1.OrchestratorServiceGrpc;
import com.doiwsc.orchestrator.v1.StartSagaRequest;
import com.doiwsc.orchestrator.v1.StartSagaResponse;
import com.doiwsc.orchestrator.v1.Step;
import io.grpc.stub.StreamObserver;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * gRPC surface for orchestrator.v1.OrchestratorService. StartSaga persists the
 * saga and returns accepted=true immediately; execution runs asynchronously on
 * the bounded worker pool. GetSaga returns the full step history.
 */
@Component
public class OrchestratorGrpcService extends OrchestratorServiceGrpc.OrchestratorServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorGrpcService.class);

    private final SagaStarter sagaStarter;
    private final SagaRepository sagaRepository;
    private final SagaStepRepository stepRepository;

    public OrchestratorGrpcService(SagaStarter sagaStarter,
                                   SagaRepository sagaRepository,
                                   SagaStepRepository stepRepository) {
        this.sagaStarter = sagaStarter;
        this.sagaRepository = sagaRepository;
        this.stepRepository = stepRepository;
    }

    @Override
    public void startSaga(StartSagaRequest request, StreamObserver<StartSagaResponse> observer) {
        List<StockLine> items = request.getItemsList().stream()
                .map(i -> new StockLine(i.getSku(), i.getQuantity()))
                .toList();
        SagaStarter.StartResult result = sagaStarter.start(
                request.getOrderId(), request.getCustomerId(), request.getRecipient(),
                items, request.getAmountCents(), request.getCurrency());
        StartSagaResponse.Builder builder = StartSagaResponse.newBuilder()
                .setAccepted(result.accepted())
                .setSagaId(result.sagaId());
        if (result.duplicate()) {
            builder.setFailureReason("");
        }
        observer.onNext(builder.build());
        observer.onCompleted();
    }

    @Override
    public void getSaga(GetSagaRequest request, StreamObserver<GetSagaResponse> observer) {
        Saga saga = sagaRepository.findById(request.getSagaId()).orElse(null);
        if (saga == null) {
            observer.onNext(GetSagaResponse.newBuilder().setFound(false).build());
            observer.onCompleted();
            return;
        }
        List<Step> steps = stepRepository.findBySagaIdOrderByIdAsc(saga.getSagaId()).stream()
                .map(this::toStep)
                .toList();
        observer.onNext(GetSagaResponse.newBuilder()
                .setFound(true)
                .setSagaId(saga.getSagaId())
                .setOrderId(saga.getOrderId())
                .setState(saga.getState().name())
                .addAllSteps(steps)
                .build());
        observer.onCompleted();
    }

    private Step toStep(SagaStep s) {
        Step.Builder b = Step.newBuilder()
                .setName(s.getName())
                .setStatus(s.getStatus().name())
                .setDetail(s.getDetail() == null ? "" : s.getDetail())
                .setStartedAtUnixMs(s.getStartedAtUnixMs() == null ? 0 : s.getStartedAtUnixMs())
                .setFinishedAtUnixMs(s.getFinishedAtUnixMs() == null ? 0 : s.getFinishedAtUnixMs());
        return b.build();
    }
}
