package com.example.durable.example;

import com.example.durable.DurableContext;
import com.example.durable.Workflow;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.StructuredTaskScope;

public final class OnboardingWorkflow implements Workflow {
    @Override
    public void run(DurableContext ctx) throws Exception {
        String employeeId = ctx.step("create-record", String.class, this::createRecord);

        long laptopSeq = ctx.reserveSequence();
        long accessSeq = ctx.reserveSequence();
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            scope.fork(() -> ctx.stepWithReservedSequence(laptopSeq, "provision-laptop", String.class,
                    () -> provisionLaptop(employeeId)));
            scope.fork(() -> ctx.stepWithReservedSequence(accessSeq, "provision-access", String.class,
                    () -> provisionAccess(employeeId)));
            scope.join();
            scope.throwIfFailed();
        }

        ctx.step("welcome-email", String.class, () -> sendWelcomeEmail(employeeId));
    }

    private String createRecord() throws InterruptedException {
        Thread.sleep(50);
        return UUID.randomUUID().toString();
    }

    private String provisionLaptop(String employeeId) throws InterruptedException {
        Thread.sleep(100);
        return "laptop-for-" + employeeId;
    }

    private String provisionAccess(String employeeId) throws InterruptedException {
        Thread.sleep(120);
        return "access-for-" + employeeId;
    }

    private String sendWelcomeEmail(String employeeId) throws InterruptedException {
        Thread.sleep(30);
        return "sent-welcome-" + employeeId;
    }
}
