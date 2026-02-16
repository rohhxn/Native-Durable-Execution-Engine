package com.example.durable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class StepExecutorTest {

    @Test
    void reusesCompletedStep() throws Exception {
        Path db = Files.createTempFile("durable-test", ".db");
        String jdbcUrl = "jdbc:sqlite:" + db.toAbsolutePath();
        WorkflowRunner runner = new WorkflowRunner(jdbcUrl, Duration.ofSeconds(5));
        AtomicInteger counter = new AtomicInteger();

        Workflow workflow = ctx -> ctx.step("once", Integer.class, counter::incrementAndGet);

        runner.start("wf1", workflow, 0);
        runner.resume("wf1", workflow, 0);

        assertEquals(1, counter.get());
    }
}
