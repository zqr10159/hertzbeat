package org.apache.hertzbeat.collector.dispatch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Collection task worker thread pool with Virtual Threads (JDK 25)
 */
@Component
@Slf4j
public class WorkerPool implements DisposableBean {

    private ExecutorService workerExecutor;

    public WorkerPool() {
        initWorkExecutor();
    }

    private void initWorkExecutor() {
        ThreadFactory virtualThreadFactory = Thread.ofVirtual()
                .name("collect-vt-", 0)
                .factory();
        workerExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

        log.info("WorkerPool initialized with JDK 25 Virtual Threads successfully.");
    }

    /**
     * Run the collection task
     * @param runnable Task
     */
    public void executeJob(Runnable runnable) {
        workerExecutor.execute(runnable);
    }

    @Override
    public void destroy() {
        if (workerExecutor != null) {
            workerExecutor.close();
        }
    }
}