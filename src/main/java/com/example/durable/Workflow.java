package com.example.durable;

public interface Workflow {
    void run(DurableContext ctx) throws Exception;
}
