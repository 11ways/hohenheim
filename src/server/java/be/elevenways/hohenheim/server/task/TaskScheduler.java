package be.elevenways.hohenheim.server.task;

import be.elevenways.protoblast.common.Blast;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs periodic maintenance tasks on a shared scheduler.
 */
public class TaskScheduler {

    private final ScheduledExecutorService scheduler;

    public TaskScheduler() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hohenheim-tasks");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Schedule a task to run at a fixed interval after an initial delay.
     */
    public void schedule(String name, Runnable task, long initialDelayMinutes, long intervalMinutes) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                task.run();
            } catch (Exception e) {
                Blast.log("TASK:", name, "failed:", e.getMessage());
            }
        }, initialDelayMinutes, intervalMinutes, TimeUnit.MINUTES);
    }

    public void stop() {
        scheduler.shutdownNow();
    }
}
