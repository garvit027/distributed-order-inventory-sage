package com.doiwsc.orchestrator.fakes;

import com.doiwsc.orchestrator.gateway.InventoryGateway;
import com.doiwsc.orchestrator.gateway.StockLine;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.List;

/** In-memory InventoryGateway with configurable behaviour and call recording. */
public class FakeInventoryGateway implements InventoryGateway {

    public enum ReserveMode { SUCCESS, INSUFFICIENT_STOCK, UNAVAILABLE }

    public volatile ReserveMode reserveMode = ReserveMode.SUCCESS;
    public volatile int reserveAttempts = 0;
    public final List<String> releasedReservations = new ArrayList<>();
    public final List<String> reserveKeys = new ArrayList<>();

    @Override
    public ReserveResult reserve(String idempotencyKey, String orderId, List<StockLine> items) {
        reserveAttempts++;
        reserveKeys.add(idempotencyKey);
        return switch (reserveMode) {
            case SUCCESS -> new ReserveResult(true, "resv_1", null);
            case INSUFFICIENT_STOCK -> new ReserveResult(false, "", "INSUFFICIENT_STOCK");
            case UNAVAILABLE -> throw new StatusRuntimeException(Status.UNAVAILABLE);
        };
    }

    @Override
    public ReleaseResult release(String idempotencyKey, String reservationId) {
        releasedReservations.add(reservationId);
        return new ReleaseResult(true, null);
    }
}
