/*
 * MIT License
 *
 * Copyright (c) 2022 Qingtian Wang
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package conottle;

import elf4j.Logger;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.ToString;
import org.apache.commons.pool2.ObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.*;

/**
 * Provides throttling on concurrent tasks per client, and on total number of clients serviced concurrently.
 */
@ThreadSafe
@ToString
public final class Conottle implements ClientTaskExecutor, AutoCloseable {
    private static final int DEFAULT_MAX_TOTAL_CLIENTS_IN_PARALLEL =
            Math.max(16, Runtime.getRuntime().availableProcessors());
    private static final int DEFAULT_MAX_CONCURRENCY_PER_CLIENT =
            Math.max(16, Runtime.getRuntime().availableProcessors());
    private static final Logger logger = Logger.instance();
    private final ExecutorService adminExecutorService = Executors.newSingleThreadExecutor();
    private final ConcurrentMap<Object, TaskCountingExecutor> activeThrottlingExecutors;
    private final ObjectPool<TaskCountingExecutor> throttlingExecutorPool;

    private Conottle(@NonNull Builder builder) {
        this.activeThrottlingExecutors = new ConcurrentHashMap<>(builder.maxTotalClientsInParallel);
        this.throttlingExecutorPool =
                new GenericObjectPool<>(new PooledExecutorFactory(builder.maxConcurrencyPerClient),
                        getThrottlingExecutorPoolConfig(builder.maxTotalClientsInParallel));
        logger.atTrace().log("Success constructing: {}", this);
    }

    @NonNull
    private static GenericObjectPoolConfig<TaskCountingExecutor> getThrottlingExecutorPoolConfig(int poolSizeMaxTotal) {
        GenericObjectPoolConfig<TaskCountingExecutor> throttlingExecutorPoolConfig = new GenericObjectPoolConfig<>();
        throttlingExecutorPoolConfig.setMaxTotal(poolSizeMaxTotal);
        return throttlingExecutorPoolConfig;
    }

    @Override
    @NonNull
    public CompletableFuture<Void> execute(@NonNull Runnable command, @NonNull Object clientId) {
        return submit(Executors.callable(command, null), clientId);
    }

    @Override
    @NonNull
    public <V> CompletableFuture<V> submit(@NonNull Callable<V> task, @NonNull Object clientId) {
        CompletableFutureHolder<V> taskCompletableFutureHolder = new CompletableFutureHolder<>();
        activeThrottlingExecutors.compute(clientId, (k, presentExecutor) -> {
            TaskCountingExecutor executor = (presentExecutor == null) ? borrowFromPool() : presentExecutor;
            taskCompletableFutureHolder.setCompletableFuture(executor.incrementPendingTaskCountAndSubmit(task));
            return executor;
        });
        CompletableFuture<V> taskCompletableFuture = taskCompletableFutureHolder.getCompletableFuture();
        CompletableFuture<V> copy = taskCompletableFuture.thenApply(r -> r);
        taskCompletableFuture.whenCompleteAsync((r, e) -> activeThrottlingExecutors.computeIfPresent(clientId,
                (k, checkedExecutor) -> {
                    if (checkedExecutor.decrementAndGetPendingTaskCount() == 0) {
                        returnToPool(checkedExecutor);
                        return null;
                    }
                    return checkedExecutor;
                }), adminExecutorService);
        return copy;
    }

    @Override
    public void close() {
        this.throttlingExecutorPool.close();
    }

    int countActiveExecutors() {
        return activeThrottlingExecutors.size();
    }

    private TaskCountingExecutor borrowFromPool() {
        try {
            return throttlingExecutorPool.borrowObject();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to borrow executor from pool " + throttlingExecutorPool, e);
        }
    }

    private void returnToPool(TaskCountingExecutor throttlingExecutor) {
        adminExecutorService.submit(() -> {
            try {
                throttlingExecutorPool.returnObject(throttlingExecutor);
            } catch (Exception e) {
                logger.atWarn()
                        .log(e, "Ignoring failure of returning {} to {}", throttlingExecutor, throttlingExecutorPool);
            }
        });
    }

    /**
     * Builder that can customize throttle limit on per-client concurrent tasks, and/or limit on total number of clients
     * concurrently serviced
     */
    @NoArgsConstructor
    public static final class Builder {
        private int maxTotalClientsInParallel = DEFAULT_MAX_TOTAL_CLIENTS_IN_PARALLEL;
        private int maxConcurrencyPerClient = DEFAULT_MAX_CONCURRENCY_PER_CLIENT;

        /**
         * @return the concurrent throttler instance
         */
        @NonNull
        public Conottle build() {
            return new Conottle(this);
        }

        /**
         * @param val
         *         max number of clients that can be concurrent serviced
         * @return the same builder instance
         */
        public Builder maxTotalClientsInParallel(int val) {
            if (val < 1) {
                throw new IllegalArgumentException(
                        "max client total in parallel should be greater than 1 but is: " + val);
            }
            maxTotalClientsInParallel = val;
            return this;
        }

        /**
         * @param val
         *         max number of tasks that can be concurrently executed per each client
         * @return the name builder instance
         */
        public Builder maxConcurrencyPerClient(int val) {
            if (val < 1) {
                throw new IllegalArgumentException(
                        "max concurrency per client should be greater than 1 but is: " + val);
            }
            maxConcurrencyPerClient = val;
            return this;
        }
    }

    @Data
    private static final class CompletableFutureHolder<V> {
        private CompletableFuture<V> completableFuture;
    }
}
