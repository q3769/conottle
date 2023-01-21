# conottle

A Java concurrent API to throttle the maximum concurrency to process tasks for any given client while the total number
of clients being serviced in parallel can also be throttled

- **conottle** is short for **con**currency thr**ottle**.

## User story

As an API user, I want to execute tasks for any given client with a configurable maximum concurrency while the total
number of clients being serviced in parallel can also be limited.

## Prerequisite

Java 8 or better

## Get it...

[![Maven Central](https://img.shields.io/maven-central/v/io.github.q3769/conottle.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.github.q3769%22%20AND%20a:%22conottle%22)

## Use it...

### API

```java
public interface ConcurrentThrottler {
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

### Sample usage

```java

@Nested
class submit {
    @Test
    void customized() {
        Conottle conottle = new Conottle.Builder().maxSingleClientConcurrency(4).maxParallelClientCount(100).build();
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

- `maxSingleClientConcurrency` is the maximum concurrency at which one single client's tasks can execute. If omitted,
  the default is `Runtime.getRuntime().availableProcessors()`.

- `maxParallelClientCount` is the maximum number of clients that can be serviced in parallel. If omitted, the default is
  unbounded (`Integer.MAX_VALUE`).

Note that, regardless of the builder parameter values, there is no overall limit on how many clients or tasks the API
can support. The parameters only limit the service concurrency of the API at a given moment. Before proceeding,
excessive clients/tasks will have to wait for active ones to run for completion - i.e. the throttling effect.

Each throttled client has its own dedicated executor. The executor is backed by a worker thread pool of maximum
size `maxSingleClientConcurrency`. Therefore, a client/executor's task execution concurrency will never go beyond, and
always be throttled at its `maxSingleClientConcurrency`.

The individual worker thread pools themselves are then also pooled, at a maximum pool size of `maxParallelClientCount`.
This limits the total number of clients that can be serviced in parallel.

If both builder parameters are provided, the global maximum number of concurrent-execution threads at any moment is
the `maxSingleClientConcurrency` multiplied by the `maxParallelClientCount`.

Builder parameters can also be individually provided. E.g. a conottle instance from the following builder will throttle
the concurrency of each client's tasks at 4, and has no limit on the total number of clients serviced in parallel:

```jshelllanguage
ConcurrentThrottler conottle = new Conottle.Builder().maxSingleClientConcurrency(4).build();
```
