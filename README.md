# conottle

A Java concurrent API to throttle the maximum concurrency to process tasks of a given client while the total number of
clients being serviced in parallel can also be throttled

- **conottle** is short for **con**current thr**ottle**.

## User story

As an API user, I want to execute all tasks of a given client with a configurable maximum concurrency while, optionally,
the total number of clients being serviced in parallel can also be throttled.

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
     * @return {@link Future} holding the run status of the {@code command}
     */
    Future<Void> execute(Runnable command, Object clientId);

    /**
     * @param task     {@link Callable} task to run asynchronously. All such tasks under the same {@code clientId} are
     *                 run in parallel, albeit throttled at a maximum concurrency.
     * @param clientId A key representing a client whose tasks are throttled while running in parallel
     * @return {@link Future} representing the result of the {@code task}
     */
    <V> Future<V> submit(Callable<V> task, Object clientId);
}
```

### Sample usage

```java

@Nested
class submit {
    @Test
    void customized() throws ExecutionException, InterruptedException {
        Conottle conottle = new Conottle.Builder().throttleLimit(4).activeClientLimit(50).build();
        String clientId1 = "clientId1";
        String clientId2 = "clientId2";
        int totalTasksPerClient = 10;

        List<Future<Task>> futures = new ArrayList<>();
        for (int i = 0; i < totalTasksPerClient; i++) {
            futures.add(conottle.submit(new Task(clientId1 + "-task-" + i, MIN_TASK_DURATION), clientId1));
            futures.add(conottle.submit(new Task(clientId2 + "-task-" + i, MIN_TASK_DURATION), clientId2));
        }

        int totalClients = 2;
        assertEquals(totalClients, conottle.totalActiveExecutors(), "should be 1:1 between a client and its executor");
        info.log("none of {} will be done right away", futures);
        for (Future<Task> future : futures) {
            assertFalse(future.isDone());
        }
        info.log("all of {} will be done eventually", futures);
        for (Future<Task> future : futures) {
            await().until(future::isDone);
            assertTrue(future.get().isComplete());
        }
        info.log("no active executor lingers when all tasks complete");
        await().until(() -> conottle.totalActiveExecutors() == 0);
    }
}
```

Both builder parameters are optional:

- `throttleLimit` is the throttle/maximum concurrency with which a given client's tasks can execute. If omitted, the
  default is the number of the available processors to the JVM runtime - `Runtime.getRuntime().availableProcessors()`.

- `activeClientLimit` is the throttle/maximum number of clients that can be serviced in parallel. If omitted, the
  default is unbounded - `Integer.MAX_VALUE`.

Note that, regardless of the parameter values, there is no limit on how many overall clients or tasks the API can
support. E.g. the `activeClientLimit` parameter only limits on the concurrent number of clients whose tasks are actively
executing in parallel at any given moment. Excessive clients/tasks will have to wait for active ones to complete before
proceeding - a.k.a. the throttling effect.

Each throttled client has its own dedicated executor. The executor is backed by a thread pool of size `throttleLimit`.
Therefore, a client/executor's task execution concurrency will never go beyond, and always be throttled at
its `throttleLimit`.

If both builder parameters are provided, the global maximum number of concurrent-execution threads is
the `throttleLimit` of each client/executor, multiplied by the `activeClientLimit`.

An all-default builder builds a conottle instance that has unbounded `activeClientLimit`, while the `throttleLimit` of
each client is set at `Runtime.getRuntime().availableProcessors()`:

```kotlin
ConcurrentThrottler conottle = new Conottle.Builder().build();
```

Builder parameters can also be individually provided. E.g. a conottle instance from the following builder throttles the
max concurrency of each client's tasks at 4, and has no limit on the total number of clients serviced in parallel:

```kotlin
ConcurrentThrottler conottle = new Conottle.Builder().throttleLimit(4).build();
```
