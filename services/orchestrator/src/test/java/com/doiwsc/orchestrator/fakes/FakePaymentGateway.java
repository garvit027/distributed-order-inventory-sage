package com.doiwsc.orchestrator.fakes;

import com.doiwsc.orchestrator.gateway.PaymentGateway;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.List;

/** In-memory PaymentGateway with configurable behaviour and call recording. */
public class FakePaymentGateway implements PaymentGateway {

    public enum ChargeMode { SUCCESS, CARD_DECLINED, UNAVAILABLE }

    public volatile ChargeMode chargeMode = ChargeMode.SUCCESS;
    public volatile int chargeAttempts = 0;
    public final List<String> refundedPayments = new ArrayList<>();
    public final List<String> chargeKeys = new ArrayList<>();

    @Override
    public ChargeResult charge(String idempotencyKey, String orderId, String customerId,
                               long amountCents, String currency) {
        chargeAttempts++;
        chargeKeys.add(idempotencyKey);
        return switch (chargeMode) {
            case SUCCESS -> new ChargeResult(true, "pay_1", null);
            case CARD_DECLINED -> new ChargeResult(false, "", "CARD_DECLINED");
            case UNAVAILABLE -> throw new StatusRuntimeException(Status.UNAVAILABLE);
        };
    }

    @Override
    public RefundResult refund(String idempotencyKey, String paymentId, String orderId) {
        refundedPayments.add(paymentId);
        return new RefundResult(true, null);
    }
}
