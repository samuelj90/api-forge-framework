package com.company.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Orders Service — API-Forge example application.
 *
 * <p>Generated code lives in:
 * <ul>
 *   <li>{@code target/generated-sources/api-forge/} — never edit these</li>
 * </ul>
 *
 * <p>Hand-written code lives in:
 * <ul>
 *   <li>{@code service/impl/} — the only files you write</li>
 *   <li>{@code exception/} — optional custom exception handlers</li>
 *   <li>{@code config/} — optional Spring configuration beans</li>
 * </ul>
 */
@SpringBootApplication
public class OrdersServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrdersServiceApplication.class, args);
    }
}
