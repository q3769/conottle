[![](https://img.shields.io/static/v1?label=github&message=repo&color=blue)](https://github.com/q3769/conottle)

A Java concurrent API to throttle the maximum concurrency to process tasks for any given client while the total number
of clients being serviced in parallel can also be throttled

- **conottle** is short for **con**currency thr**ottle**.

# User Story

As an API user, I want to execute tasks for any given client with a configurable maximum concurrency while the total
number of clients being serviced in parallel can also be limited.

# Prerequisite

Java 8 or better

# Get It...

[![Maven Central](https://img.shields.io/maven-central/v/io.github.q3769/conottle.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.github.q3769%22%20AND%20a:%22conottle%22)

# Use It...

## API

```java
public interface ClientTaskExecutor {
    /**
     * @param command  {@link Runnable} command to run asynchronously. All such commands under the same {@code clientId}
     *                 are run in parallel, albeit throttled at a maximum concurrency.
     * @param clientId A key representing a client whose tasks are throttled while running in parallel
     * @return {@link java.util.concurrent.Future} holding the run status of the {@code command}
     */
    Future<Void> execute(Runnable command, Object clientId);

    /**
     * @param task     {@link java.util.concurrent.Callable} task to run asynchronously. All such tasks under the same {@code clientId} are
     *                 run in parallel, albeit throttled at a maximum concurrency.
     * @param clientId A key representing a client whose tasks are throttled while running in parallel
     * @param <V>      Type of the task result
     * @return {@link java.util.concurrent.Future} representing the result of the {@code task}
     */
    <V> Future<V> submit(Callable<V> task, Object clientId);
}
```

The interface uses `Future` as the return type, mainly to reduce conceptual weight of the API. The implementation
actually returns `CompletableFuture`, and can be used directly if need be.

## Sample Usage

```java

@Nested
class submit {
    @Test
    void customized() {
        Conottle conottle = new Conottle.Builder().singleClientMaxConcurrency(4).concurrentClientMaxTotal(100).build();
        int clientCount = 2;
        int clientTaskCount = 10;

        List<Future<Task>> futures = new ArrayList<>(); // class Task implements Callable<Task>
        for (int c = 0; c < clientCount; c++) {
            String clientId = "clientId-" + (c + 1);
            for (int t = 0; t < clientTaskCount; t++) {
                futures.add(conottle.submit(new Task(clientId + "-task-" + t, MIN_TASK_DURATION), clientId));
            }
        }

        assertEquals(clientCount, conottle.countActiveExecutors(), "should be 1:1 between a client and its executor");
        int taskTotal = futures.size();
        assertEquals(clientTaskCount * clientCount, taskTotal);
        info.log("none of {} tasks will be done immediately", taskTotal);
        for (Future<Task> future : futures) {
            assertFalse(future.isDone());
        }
        info.log("all of {} tasks will be done eventually", taskTotal);
        for (Future<Task> future : futures) {
            await().until(future::isDone);
        }
        info.log("no active executor lingers when all tasks complete");
        await().until(() -> conottle.countActiveExecutors() == 0);
    }
}
```

Both builder parameters are optional:

- `singleClientMaxConcurrency` is the maximum concurrency at which one single client's tasks can execute. If omitted or
  set to a non-positive integer, then the default is the larger between 16
  and `Runtime.getRuntime().availableProcessors()`.
- `concurrentClientMaxTotal` is the maximum number of clients that can be serviced in parallel. If omitted or set to a
  non-positive integer, then the default is the larger between 16
  and `Runtime.getRuntime().availableProcessors()`.

Regardless of the builder parameters, there is no programmatic limit on the total number of tasks or clients supported
by the API. The only limit is on runtime concurrency at any given moment: Before proceeding, excessive tasks or clients
will have to wait for active ones to run for completion - i.e. the throttling effect.

Each individual client can have only one single dedicated executor at any given moment. The executor is backed by a
worker thread pool with maximum size `singleClientMaxConcurrency`. Thus the client's execution concurrency can never go
beyond, and will always be throttled at `singleClientMaxConcurrency`. The individual executors themselves are then
pooled collectively, at a maximum pool size of `concurrentClientMaxTotal`; this throttles the total number of clients
that can be serviced in parallel.

If both builder parameters are provided, the `Conottle` instance's maximum number of concurrent threads is
the `singleClientMaxConcurrency` multiplied by the `concurrentClientMaxTotal`.
