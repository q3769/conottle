/*
 * MIT License
 *
 * Copyright (c) 2023 Qingtian Wang
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
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
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
import java.time.Duration;
import java.util.concurrent.*;

import static java.lang.Math.max;

/**
 * Provides throttling on current tasks per client, and total number of clients serviced concurrently.
 */
@ThreadSafe
@ToString
public final class Conottle implements ConcurrentThrottler {
    private static final ExecutorService ADMIN_EXECUTOR_SERVICE = Executors.newCachedThreadPool();
    private static final int DEFAULT_MAX_ACTIVE_EXECUTORS = Integer.MAX_VALUE;
    private static final int DEFAULT_MAX_IDLE_EXECUTORS = max(16, Runtime.getRuntime().availableProcessors());
    private static final int DEFAULT_MIN_IDLE_EXECUTORS = DEFAULT_MAX_IDLE_EXECUTORS / 2;
    private static final int DEFAULT_THROTTLE_LIMIT = Runtime.getRuntime().availableProcessors();
    private static final Duration MIN_EVICTABLE_IDLE_TIME = Duration.ofMinutes(5);
    private static final Logger logger = Logger.instance();
    private final ConcurrentMap<Object, ClientTaskExecutor> activeExecutors;
    private final ObjectPool<ExecutorService> throttlingClientTaskServicePool;

    private Conottle(@NonNull Builder builder) {
        if (builder.throttleLimit < 0 || builder.concurrentClientLimit < 0) {
            throw new IllegalArgumentException(
                    "neither per client throttle limit nor max concurrent client limit can be negative but was given: "
                            + builder);
        }
        this.activeExecutors = new ConcurrentHashMap<>();
        this.throttlingClientTaskServicePool = new GenericObjectPool<>(new ThrottlingExecutorServiceFactory(
                builder.throttleLimit == 0 ? DEFAULT_THROTTLE_LIMIT : builder.throttleLimit),
                getThrottlingExecutorServicePoolConfig(
                        builder.concurrentClientLimit == 0 ? DEFAULT_MAX_ACTIVE_EXECUTORS :
                                builder.concurrentClientLimit));
        logger.atInfo().log("constructed {}", this);
    }

    @NonNull
    private static GenericObjectPoolConfig<ExecutorService> getThrottlingExecutorServicePoolConfig(int maxTotal) {
        GenericObjectPoolConfig<ExecutorService> throttlingExecutorServicePoolConfig = new GenericObjectPoolConfig<>();
        throttlingExecutorServicePoolConfig.setMaxTotal(maxTotal);
        throttlingExecutorServicePoolConfig.setMaxIdle(DEFAULT_MAX_IDLE_EXECUTORS);
        throttlingExecutorServicePoolConfig.setMinIdle(DEFAULT_MIN_IDLE_EXECUTORS);
        throttlingExecutorServicePoolConfig.setMinEvictableIdleTime(MIN_EVICTABLE_IDLE_TIME);
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
        activeExecutors.compute(clientId, (sameClientId, presentExecutor) -> {
            ClientTaskExecutor executor =
                    presentExecutor == null ? new ClientTaskExecutor(borrowPooledExecutorService()) : presentExecutor;
            taskStageHolder.setStage(executor.submit(task));
            return executor;
        });
        CompletableFuture<V> taskStage = taskStageHolder.getStage();
        taskStage.whenCompleteAsync((r, e) -> activeExecutors.computeIfPresent(clientId,
                (sameClientId, checkedExecutor) -> {
                    if (checkedExecutor.decrementAndGetPendingTasks() == 0) {
                        returnToPoolIgnoreError(checkedExecutor.throttlingExecutorService);
                        return null;
                    }
                    return checkedExecutor;
                }), ADMIN_EXECUTOR_SERVICE);
        return new MinimalFuture<>(taskStage);
    }

    private ExecutorService borrowPooledExecutorService() {
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

    int countActiveExecutors() {
        return activeExecutors.size();
    }

    /**
     * Builder that can customize throttle limit on per-client concurrent tasks, and/or limit on total number of clients
     * concurrently serviced
     */
    @ToString
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
            this.concurrentClientLimit = val;
            return this;
        }

        /**
         * @param val max number of tasks that can be concurrently executed per each client
         * @return the name builder instance
         */
        public Builder throttleLimit(int val) {
            throttleLimit = val;
            return this;
        }
    }

    /**
     * Not thread safe; needs to be synchronized.
     */
    @NotThreadSafe
    @ToString
    private static class ClientTaskExecutor {
        private final ExecutorService throttlingExecutorService;
        private int pendingTasks;

        public ClientTaskExecutor(ExecutorService throttlingExecutorService) {
            this.throttlingExecutorService = throttlingExecutorService;
        }

        public int decrementAndGetPendingTasks() {
            if (pendingTasks <= 0) {
                throw new IllegalStateException("cannot further decrement from pending task count: " + pendingTasks);
            }
            return --pendingTasks;
        }

        @NonNull
        public <V> CompletableFuture<V> submit(Callable<V> task) {
            pendingTasks++;
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.call();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, throttlingExecutorService);
        }
    }

    @RequiredArgsConstructor
    private static class MinimalFuture<V> implements Future<V> {
        @Delegate private final Future<V> delegate;
    }

    @Data
    private static class TaskStageHolder<V> {
        private CompletableFuture<V> stage;
    }

    private static class ThrottlingExecutorServiceFactory extends BasePooledObjectFactory<ExecutorService> {
        private final int throttleLimit;

        public ThrottlingExecutorServiceFactory(int throttleLimit) {
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
                                "ignoring super-call error while destroying {} with {} mode",
                                pooledExecutorService,
                                destroyMode);
            }
            pooledExecutorService.getObject().shutdown();
        }
    }
}
