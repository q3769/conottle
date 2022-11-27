# conottle

A Java concurrent API to throttle the maximum concurrency to process tasks of a given client while the total number of
clients being serviced in parallel can also be throttled. "conottle" is short of **con**current thr***ottle***.

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
     * @param command    {@link Runnable} command to run asynchronously. All such commands under the same
     *                   {@code throttleId} are run in parallel, albeit throttled at a maximum concurrency.
     * @param throttleId A key representing a client whose tasks are throttled while running in parallel
     * @return {@link Future} holding the run status of the {@code command}
     */
    Future<Void> execute(Runnable command, Object throttleId);

    /**
     * @param task       {@link Callable} task to run asynchronously. All such tasks under the same {@code throttleId}
     *                   are run in parallel, albeit throttled at a maximum concurrency.
     * @param throttleId A key representing a client whose tasks are throttled while running in parallel
     * @return {@link Future} representing the result of the {@code task}
     */
    <V> Future<V> submit(Callable<V> task, Object throttleId);
}
```

### Sample usage

```aidl
class submit {
    @Test
    void customizedConottle() {
        int maxActiveExecutors = 4;
        int throttleLimit = 3;
        Conottle conottle =
                new Conottle.Builder().throttleLimit(throttleLimit).maxActiveExecutors(maxActiveExecutors).build();
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
        }
        info.log("no executor lingers when all tasks complete");
        await().until(() -> conottle.sizeOfActiveExecutors() == 0);
    }
}
```

Both builder parameters - `throttleLimit` and `maxActiveExecutors` - provided in the above sample are optional. If both
are provided, then potentially the max total number of concurrent running threads is the `throttleLimit` on each
executor multiplied by the `maxActiveExecutors`.

An all-default builder builds a conottle instance that has no limit (`Integer.MAX_VALUE`) on the `maxActiveExecutors`,
while the default `throttleLimit` for each client's tasks is the number of the available processors of the JVM
runtime - `Runtime.getRuntime().availableProcessors()`:

```aidl
ConcurrentThrottle conottle = new Conottle.Builder().build();
```

Builder parameters can also be individually provided. E.g. a conottle instance from this builder throttles the max
concurrency of each client's tasks at 4, and has no limit on the total number of clients in parallel:

```aidl
ConcurrentThrottle conottle = new Conottle.Builder().throttleLimit(4).build();
```
