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
import lombok.*;
import lombok.experimental.Delegate;
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
 * Provides throttling on current tasks per client, and total number of clients serviced concurrently.
 */
@ThreadSafe
@ToString
public final class Conottle implements ConcurrentThrottler {
    private static final ExecutorService ADMIN_EXECUTOR_SERVICE = Executors.newCachedThreadPool();
    private static final int DEFAULT_MAX_ACTIVE_EXECUTORS = Integer.MAX_VALUE;
    private static final int DEFAULT_THROTTLE_LIMIT = Runtime.getRuntime().availableProcessors();
    private static final Logger logger = Logger.instance();
    private final ConcurrentMap<Object, ClientTaskExecutor> activeExecutors;
    private final ObjectPool<ExecutorService> throttlingClientTaskServicePool;

    private Conottle(@NonNull Builder builder) {
        this.activeExecutors = new ConcurrentHashMap<>();
        this.throttlingClientTaskServicePool = new GenericObjectPool<>(new ClientExecutorServiceFactory(
                builder.throttleLimit == 0 ? DEFAULT_THROTTLE_LIMIT : builder.throttleLimit),
                getClientExecutorServicePoolConfig(builder.concurrentClientLimit == 0 ? DEFAULT_MAX_ACTIVE_EXECUTORS :
                        builder.concurrentClientLimit));
        logger.atInfo().log("constructed {}", this);
    }

    @NonNull
    private static GenericObjectPoolConfig<ExecutorService> getClientExecutorServicePoolConfig(int maxConcurrentlyServicedClients) {
        GenericObjectPoolConfig<ExecutorService> throttlingExecutorServicePoolConfig = new GenericObjectPoolConfig<>();
        throttlingExecutorServicePoolConfig.setMaxTotal(maxConcurrentlyServicedClients);
        return throttlingExecutorServicePoolConfig;
    }

    @Override
    @NonNull
    public Future<Void> execute(@NonNull Runnable command, @NonNull Object clientId) {
        return submit(Executors.callable(command, null), clientId);
    }

    @Override
    @NonNull
    public <V> Future<V> submit(@NonNull Callable<V> task, @NonNull Object clientId) {
        TaskStageHolder<V> taskStageHolder = new TaskStageHolder<>();
        activeExecutors.compute(clientId, (sameClientId, presentClientTaskExecutor) -> {
            ClientTaskExecutor executor =
                    presentClientTaskExecutor == null ? new ClientTaskExecutor(borrowFromPoolFailFast()) :
                            presentClientTaskExecutor;
            taskStageHolder.setStage(executor.submit(task));
            return executor;
        });
        CompletableFuture<V> taskStage = taskStageHolder.getStage();
        taskStage.whenCompleteAsync((r, e) -> activeExecutors.computeIfPresent(clientId,
                (sameClientId, checkedClientTaskExecutor) -> {
                    if (checkedClientTaskExecutor.decrementAndGetPendingTaskCount() == 0) {
                        returnToPoolIgnoreError(checkedClientTaskExecutor.getExecutorService());
                        return null;
                    }
                    return checkedClientTaskExecutor;
                }), ADMIN_EXECUTOR_SERVICE);
        return new MinimalFuture<>(taskStage);
    }

    int countActiveExecutors() {
        return activeExecutors.size();
    }

    private ExecutorService borrowFromPoolFailFast() {
        try {
            return throttlingClientTaskServicePool.borrowObject();
        } catch (Exception e) {
            throw new IllegalStateException("failed to borrow executor from pool " + throttlingClientTaskServicePool,
                    e);
        }
    }

    private void returnToPoolIgnoreError(ExecutorService executorService) {
        try {
            throttlingClientTaskServicePool.returnObject(executorService);
        } catch (Exception e) {
            logger.atWarn()
                    .log(e, "ignoring failure of returning {} to {}", executorService, throttlingClientTaskServicePool);
        }
    }

    /**
     * Builder that can customize throttle limit on per-client concurrent tasks, and/or limit on total number of clients
     * concurrently serviced
     */
    @NoArgsConstructor
    public static final class Builder {
        private int concurrentClientLimit;
        private int throttleLimit;

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
        public Builder concurrentClientLimit(int val) {
            if (val < 0) {
                throw new IllegalArgumentException(
                        "Max currently serviced client count cannot be negative but was given: " + val);
            }
            this.concurrentClientLimit = val;
            return this;
        }

        /**
         * @param val max number of tasks that can be concurrently executed per each client
         * @return the name builder instance
         */
        public Builder throttleLimit(int val) {
            if (val < 0) {
                throw new IllegalArgumentException(
                        "Throttle of concurrent execution thread count per client cannot be negative but was given: "
                                + val);
            }
            throttleLimit = val;
            return this;
        }
    }

    /**
     * Creates pooled {@link ExecutorService} instances to facilitate async client task executions. The max concurrent
     * threads of each `ExecutorService` instance will be the throttle limit of the client that the instance is
     * supporting.
     */
    private static final class ClientExecutorServiceFactory extends BasePooledObjectFactory<ExecutorService> {
        private static final Logger logger = Logger.instance();
        private final int throttleLimit;

        /**
         * @param throttleLimit max concurrent threads of the {@link ExecutorService} instance produced by this factory
         */
        public ClientExecutorServiceFactory(int throttleLimit) {
            this.throttleLimit = throttleLimit;
        }

        @Override
        @NonNull
        public ExecutorService create() {
            return Executors.newFixedThreadPool(throttleLimit);
        }

        @Override
        @NonNull
        public PooledObject<ExecutorService> wrap(ExecutorService executorService) {
            return new DefaultPooledObject<>(executorService);
        }

        @Override
        public void destroyObject(PooledObject<ExecutorService> pooledExecutorService, DestroyMode destroyMode) {
            try {
                super.destroyObject(pooledExecutorService, destroyMode);
            } catch (Exception e) {
                logger.atWarn()
                        .log(e,
                                "ignoring call-super error while destroying {} with {} mode",
                                pooledExecutorService,
                                destroyMode);
            }
            pooledExecutorService.getObject().shutdown();
        }
    }

    /**
     * Not thread safe; needs to be synchronized.
     */
    @NotThreadSafe
    @ToString
    private static final class ClientTaskExecutor {
        private final ExecutorService executorService;
        private int pendingTaskCount;

        public ClientTaskExecutor(ExecutorService executorService) {
            this.executorService = executorService;
        }

        public int decrementAndGetPendingTaskCount() {
            if (pendingTaskCount <= 0) {
                throw new IllegalStateException(
                        "cannot further decrement from pending task count: " + pendingTaskCount);
            }
            return --pendingTaskCount;
        }

        public ExecutorService getExecutorService() {
            return executorService;
        }

        @NonNull
        public <V> CompletableFuture<V> submit(Callable<V> task) {
            pendingTaskCount++;
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.call();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, executorService);
        }
    }

    @RequiredArgsConstructor
    private static final class MinimalFuture<V> implements Future<V> {
        @Delegate private final Future<V> delegate;
    }

    @Data
    private static final class TaskStageHolder<V> {
        private CompletableFuture<V> stage;
    }
}
