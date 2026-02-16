package com.example.durable;

import java.nio.file.Path;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.durable.example.OnboardingWorkflow;

public final class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);
    private static final Duration STALE_TIMEOUT = Duration.ofSeconds(10);

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java -jar app.jar <start|resume|crash-after> <workflow_id> [step_number]");
            System.exit(1);
        }

        String command = args[0];
        String workflowId = args[1];
        long crashAfter = 0;
        if ("crash-after".equals(command)) {
            if (args.length < 3) {
                System.err.println("crash-after requires a step_number");
                System.exit(1);
            }
            crashAfter = Long.parseLong(args[2]);
        }

        String jdbcUrl = "jdbc:sqlite:" + Path.of("durable.db").toAbsolutePath();
        WorkflowRunner runner = new WorkflowRunner(jdbcUrl, STALE_TIMEOUT);
        Workflow workflow = new OnboardingWorkflow();

        try {
            switch (command) {
                case "start" -> runner.start(workflowId, workflow, 0);
                case "resume" -> runner.resume(workflowId, workflow, 0);
                case "crash-after" -> runner.start(workflowId, workflow, crashAfter);
                default -> {
                    System.err.println("Unknown command: " + command);
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            log.error("Workflow failed", e);
            System.exit(1);
        }
    }
}
