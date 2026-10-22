package com.doiwsc.order.api;

import com.doiwsc.order.domain.Order;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Validates and canonicalises a create-order request. The canonical form is what
 * the idempotency request-hash is computed over, so two requests are considered
 * "the same" exactly when they would produce the same stored order.
 */
public final class RequestNormalizer {

    private RequestNormalizer() {
    }

    /** Normalised, validated view of a create request. */
    public record Normalized(
            String customerId,
            String contact,
            String currency,
            long amountCents,
            List<OrderResponse.Item> items) {
    }

    public static Normalized normalize(CreateOrderRequest req) {
        if (req == null) {
            throw bad("request body is required");
        }
        if (req.customerId() == null || req.customerId().isBlank()) {
            throw bad("customerId is required");
        }
        if (req.amountCents() == null || req.amountCents() <= 0) {
            throw bad("amountCents must be > 0");
        }
        if (req.items() == null || req.items().isEmpty()) {
            throw bad("items must be a non-empty array");
        }
        List<OrderResponse.Item> items = new ArrayList<>();
        for (CreateOrderRequest.ItemDto item : req.items()) {
            if (item == null || item.sku() == null || item.sku().isBlank()) {
                throw bad("each item requires a non-blank sku");
            }
            if (item.quantity() == null || item.quantity() < 1) {
                throw bad("each item requires quantity >= 1");
            }
            items.add(new OrderResponse.Item(item.sku().trim(), item.quantity()));
        }
        String currency = (req.currency() == null || req.currency().isBlank())
                ? "USD" : req.currency().trim();
        String contact = (req.contact() == null || req.contact().isBlank())
                ? req.customerId().trim() + "@example.com" : req.contact().trim();
        return new Normalized(req.customerId().trim(), contact, currency,
                req.amountCents(), items);
    }

    /** SHA-256 over the canonical request representation, independent of item order. */
    public static String requestHash(Normalized n) {
        StringBuilder sb = new StringBuilder();
        sb.append("customerId=").append(n.customerId())
          .append('|').append("contact=").append(n.contact())
          .append('|').append("currency=").append(n.currency())
          .append('|').append("amountCents=").append(n.amountCents())
          .append('|');
        n.items().stream()
                .sorted(Comparator.comparing(OrderResponse.Item::sku)
                        .thenComparingInt(OrderResponse.Item::quantity))
                .forEach(i -> sb.append("item:").append(i.sku()).append('x').append(i.quantity()).append(','));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static ApiException bad(String message) {
        return new ApiException(400, "VALIDATION_ERROR", message);
    }
}
