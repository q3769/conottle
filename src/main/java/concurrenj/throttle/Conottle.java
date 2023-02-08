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

package concurrenj.throttle;

import elf4j.Logger;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.ToString;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.DestroyMode;
import org.apache.commons.pool2.ObjectPool;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.*;

/**
 * Provides throttling on concurrent tasks per client, and on total number of clients serviced concurrently.
 */
@ThreadSafe
@ToString
public final class Conottle implements ClientTaskExecutor {
    private static final ExecutorService ADMIN_EXECUTOR_SERVICE = Executors.newCachedThreadPool();
    private static final int DEFAULT_MAX_PARALLEL_CLIENT_COUNT = Integer.MAX_VALUE;
    private static final int DEFAULT_MAX_SINGLE_CLIENT_CONCURRENCY = Runtime.getRuntime().availableProcessors();
    private static final Logger logger = Logger.instance();
    private final ConcurrentMap<Object, PendingTaskCountingExecutor> activeExecutors;
    private final ObjectPool<ExecutorService> throttlingExecutorServicePool;

    private Conottle(@NonNull Builder builder) {
        this.activeExecutors = new ConcurrentHashMap<>();
        this.throttlingExecutorServicePool = new GenericObjectPool<>(new ThrottlingExecutorServiceFactory(
                builder.maxSingleClientConcurrency == 0 ? DEFAULT_MAX_SINGLE_CLIENT_CONCURRENCY :
                        builder.maxSingleClientConcurrency),
                getExecutorServicePoolConfig(builder.maxParallelClientCount == 0 ? DEFAULT_MAX_PARALLEL_CLIENT_COUNT :
                        builder.maxParallelClientCount));
        logger.atTrace().log("Success constructing: {}", this);
    }

    @NonNull
    private static GenericObjectPoolConfig<ExecutorService> getExecutorServicePoolConfig(int maxConcurrentlyServicedClients) {
        GenericObjectPoolConfig<ExecutorService> throttlingExecutorServicePoolConfig = new GenericObjectPoolConfig<>();
        throttlingExecutorServicePoolConfig.setMaxTotal(maxConcurrentlyServicedClients);
        return throttlingExecutorServicePoolConfig;
    }

    @Override
    @NonNull
    public CompletableFuture<Void> execute(@NonNull Runnable command, @NonNull Object clientId) {
        return submit(Executors.callable(command, null), clientId);
    }

    @Override
    @NonNull
    public <V> CompletableFuture<V> submit(@NonNull Callable<V> task, @NonNull Object clientId) {
        TaskCompletionStageHolder<V> taskCompletionStageHolder = new TaskCompletionStageHolder<>();
        activeExecutors.compute(clientId, (k, presentPendingTaskCountingExecutor) -> {
            PendingTaskCountingExecutor executor =
                    presentPendingTaskCountingExecutor == null ? new PendingTaskCountingExecutor(borrowFromPool()) :
                            presentPendingTaskCountingExecutor;
            taskCompletionStageHolder.setStage(executor.incrementCountAndSubmit(task));
            return executor;
        });
        CompletableFuture<V> taskCompletionStage = taskCompletionStageHolder.getStage();
        taskCompletionStage.whenCompleteAsync((r, e) -> activeExecutors.computeIfPresent(clientId,
                (k, checkedPendingTaskCountingExecutor) -> {
                    if (checkedPendingTaskCountingExecutor.decrementAndGetCount() == 0) {
                        returnToPool(checkedPendingTaskCountingExecutor.getThrottlingExecutorService());
                        return null;
                    }
                    return checkedPendingTaskCountingExecutor;
                }), ADMIN_EXECUTOR_SERVICE);
        return taskCompletionStage;
    }

    int countActiveExecutors() {
        return activeExecutors.size();
    }

    private ExecutorService borrowFromPool() {
        try {
            return throttlingExecutorServicePool.borrowObject();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to borrow executor from pool " + throttlingExecutorServicePool, e);
        }
    }

    private void returnToPool(ExecutorService executorService) {
        ADMIN_EXECUTOR_SERVICE.submit(() -> {
            try {
                throttlingExecutorServicePool.returnObject(executorService);
            } catch (Exception e) {
                logger.atWarn()
                        .log(e,
                                "Ignoring failure of returning {} to {}",
                                executorService,
                                throttlingExecutorServicePool);
            }
        });
    }

    /**
     * Builder that can customize throttle limit on per-client concurrent tasks, and/or limit on total number of clients
     * concurrently serviced
     */
    @NoArgsConstructor
    public static final class Builder {
        private int maxParallelClientCount;
        private int maxSingleClientConcurrency;

        /**
         * @return the concurrent throttler instance
         */
        @NonNull
        public Conottle build() {
            return new Conottle(this);
        }

        /**
         * @param val max number of clients that can be concurrent serviced
         * @return the same builder instance
         */
        public Builder maxParallelClientCount(int val) {
            if (val < 0) {
                throw new IllegalArgumentException(
                        "Max currently serviced client count cannot be negative but was given: " + val);
            }
            maxParallelClientCount = val;
            return this;
        }

        /**
         * @param val max number of tasks that can be concurrently executed per each client
         * @return the name builder instance
         */
        public Builder maxSingleClientConcurrency(int val) {
            if (val < 0) {
                throw new IllegalArgumentException(
                        "Max task execution concurrency per each client cannot be negative but was given: " + val);
            }
            maxSingleClientConcurrency = val;
            return this;
        }
    }

    /**
     * Not thread safe; needs to be synchronized.
     */
    @NotThreadSafe
    @ToString
    private static final class PendingTaskCountingExecutor {
        private final ExecutorService throttlingExecutorService;
        private int pendingTaskCount;

        public PendingTaskCountingExecutor(ExecutorService throttlingExecutorService) {
            this.throttlingExecutorService = throttlingExecutorService;
        }

        private static <V> V call(Callable<V> task) {
            try {
                return task.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }

        public int decrementAndGetCount() {
            if (pendingTaskCount <= 0) {
                throw new IllegalStateException(
                        "Cannot further decrement from pending task count: " + pendingTaskCount);
            }
            return --pendingTaskCount;
        }

        public ExecutorService getThrottlingExecutorService() {
            return throttlingExecutorService;
        }

        @NonNull
        public <V> CompletableFuture<V> incrementCountAndSubmit(Callable<V> task) {
            pendingTaskCount++;
            return CompletableFuture.supplyAsync(() -> call(task), throttlingExecutorService);
        }
    }

    @Data
    private static final class TaskCompletionStageHolder<V> {
        private CompletableFuture<V> stage;
    }

    /**
     * Creates pooled {@link ExecutorService} instances to facilitate async client task executions. The max concurrent
     * threads of each {@code ExecutorService} instance will be the throttle limit of each client.
     */
    private static final class ThrottlingExecutorServiceFactory extends BasePooledObjectFactory<ExecutorService> {
        private final int maxExecutorServiceConcurrency;

        /**
         * @param maxExecutorServiceConcurrency max concurrent threads of the {@link ExecutorService} instance produced
         *                                      by this factory
         */
        public ThrottlingExecutorServiceFactory(int maxExecutorServiceConcurrency) {
            this.maxExecutorServiceConcurrency = maxExecutorServiceConcurrency;
        }

        @Override
        @NonNull
        public ExecutorService create() {
            return Executors.newFixedThreadPool(maxExecutorServiceConcurrency);
        }

        @Override
        @NonNull
        public PooledObject<ExecutorService> wrap(ExecutorService executorService) {
            return new DefaultPooledObject<>(executorService);
        }

        @Override
        public void destroyObject(PooledObject<ExecutorService> pooledExecutorService, DestroyMode destroyMode)
                throws Exception {
            try {
                super.destroyObject(pooledExecutorService, destroyMode);
            } finally {
                pooledExecutorService.getObject().shutdown();
            }
        }
    }
}
