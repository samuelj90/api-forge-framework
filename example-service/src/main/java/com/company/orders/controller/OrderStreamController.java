package com.company.orders.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HAND-WRITTEN controller for the SSE streaming endpoint.
 *
 * <p>This endpoint is excluded from API-Forge generation via:
 * <pre>{@code
 * <manualOperations>
 *   <operation>streamOrderEvents</operation>
 * </manualOperations>
 * }</pre>
 *
 * <p>Server-Sent Events require servlet-specific APIs that the generated
 * controller pattern doesn't support. This is a deliberate escape hatch —
 * everything else in the service is generated.
 *
 * <p>Maps to: GET /api/v1/orders/{orderId}/events
 * operationId: streamOrderEvents (as declared in orders-api.yaml)
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderStreamController {

    // private final OrderEventPublisher eventPublisher;

    /**
     * Streams real-time order lifecycle events to connected clients via SSE.
     *
     * <p>Clients receive events for status transitions:
     * PENDING → CONFIRMED → SHIPPED → DELIVERED
     */
    @GetMapping(
            value = "/{orderId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter streamOrderEvents(@PathVariable String orderId) {
        log.info("SSE stream opened for order {}", orderId);

        SseEmitter emitter = new SseEmitter(300_000L); // 5-minute timeout

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                // eventPublisher.subscribe(orderId, event -> {
                //     emitter.send(SseEmitter.event()
                //             .name(event.getType())
                //             .data(event)
                //             .id(event.getId()));
                //     if (event.isTerminal()) emitter.complete();
                // });

                // Stub: send a placeholder event
                emitter.send(SseEmitter.event()
                        .name("ORDER_STATUS")
                        .data("{\"orderId\":\"" + orderId + "\",\"status\":\"PENDING\"}"));

            } catch (IOException e) {
                log.debug("SSE client disconnected for order {}", orderId);
                emitter.completeWithError(e);
            }
        });

        emitter.onCompletion(() -> {
            executor.shutdown();
            log.debug("SSE stream completed for order {}", orderId);
        });
        emitter.onTimeout(() -> {
            executor.shutdown();
            log.debug("SSE stream timed out for order {}", orderId);
        });

        return emitter;
    }
}
