# conottle

A Java concurrent API to throttle the maximum concurrency to process tasks of a given client while the total number of
clients being serviced in parallel can also be throttled.

- "conottle" is short for **con**current thr***ottle***.

## User story

As an API user, I want to execute all tasks of a given client with a configurable maximum concurrency while, optionally,
the total number of clients being serviced in parallel can also be throttled.

## Prerequisite

Java 8 or better

## Get it...

## Use it...

### API

```aidl
public interface ConcurrentThrottle {
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

```aidl
class submit {
    @Test
    void customized() {
        Conottle conottle = new Conottle.Builder().throttleLimit(3).maxActiveClients(4).build();
        String clientId1 = "clientId1";
        String clientId2 = "clientId2";
        int totalTasksPerClient = 10;

        List<Future<Task>> futures = new ArrayList<>();
        for (int i = 0; i < totalTasksPerClient; i++) {
            futures.add(conottle.submit(new Task(clientId1 + "-task-" + i, Duration.of(100, MILLIS)), clientId1));
            futures.add(conottle.submit(new Task(clientId2 + "-task-" + i, Duration.of(100, MILLIS)), clientId2));
        }

        int totalClients = 2;
        assertEquals(totalClients, conottle.sizeOfActiveExecutors());
        for (Future<Task> future : futures) {
            info.log("{} will not get done right away", future);
            assertFalse(future.isDone());
        }
        for (Future<Task> future : futures) {
            info.log("but eventually {} will be done", future);
            await().until(future::isDone);
            try {
                assertTrue(future.get().isComplete());
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
        info.log("no active executor lingers when all tasks complete");
        await().until(() -> conottle.sizeOfActiveExecutors() == 0);
    }
}
```

Both builder parameters are optional.

- `throttleLimit` is the maximum concurrency with which a given client's tasks can execute. If omitted, the default is
  the number of the available processors to the JVM runtime - `Runtime.getRuntime().availableProcessors()`.

- `maxActiveClients` is the maximum total number of clients that can be serviced in parallel. If omitted, the default
  is no limit - `Integer.MAX_VALUE`.

Each client has its own dedicated executor. The executor is backed by a thread pool of size `throttleLimit`. Therefore,
the client/executor's task execution concurrency will never go beyond, and always be throttled at the `throttleLimit`.

If both builder parameters are provided, the global maximum number of concurrently running threads is
the `throttleLimit` of each client/executor multiplied by the `maxActiveClients`.

An all-default builder builds a conottle instance that has no limit on the `maxActiveClients`, while the `throttleLimit`
of each client is set at `Runtime.getRuntime().availableProcessors()`:

```aidl
ConcurrentThrottle conottle = new Conottle.Builder().build();
```

Builder parameters can also be individually provided. E.g. a conottle instance from the following builder throttles the
max concurrency of each client's tasks at 4, and has no limit on the total number of clients in parallel:

```aidl
ConcurrentThrottle conottle = new Conottle.Builder().throttleLimit(4).build();
```
