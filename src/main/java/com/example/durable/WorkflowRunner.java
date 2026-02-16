package com.example.durable;

import java.time.Duration;

public final class WorkflowRunner {
    private final String jdbcUrl;
    private final Duration staleTimeout;

    public WorkflowRunner(String jdbcUrl, Duration staleTimeout) {
        this.jdbcUrl = jdbcUrl;
        this.staleTimeout = staleTimeout;
    }

    public void start(String workflowId, Workflow workflow, long crashAfter) throws Exception {
        DurableContext ctx = new DurableContext(workflowId, jdbcUrl, crashAfter, staleTimeout);
        workflow.run(ctx);
    }

    public void resume(String workflowId, Workflow workflow, long crashAfter) throws Exception {
        DurableContext ctx = new DurableContext(workflowId, jdbcUrl, crashAfter, staleTimeout);
        workflow.run(ctx);
    }
}
