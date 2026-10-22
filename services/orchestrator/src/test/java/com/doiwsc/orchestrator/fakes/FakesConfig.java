package com.doiwsc.orchestrator.fakes;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Installs the in-memory fakes as @Primary so the engine never opens a socket. */
@TestConfiguration
public class FakesConfig {

    @Bean
    @Primary
    public FakeInventoryGateway fakeInventoryGateway() {
        return new FakeInventoryGateway();
    }

    @Bean
    @Primary
    public FakePaymentGateway fakePaymentGateway() {
        return new FakePaymentGateway();
    }

    @Bean
    @Primary
    public FakeOrderGateway fakeOrderGateway() {
        return new FakeOrderGateway();
    }

    @Bean
    @Primary
    public FakeNotificationGateway fakeNotificationGateway() {
        return new FakeNotificationGateway();
    }
}
