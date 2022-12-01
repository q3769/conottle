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
import lombok.ToString;
import lombok.experimental.Delegate;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.DestroyMode;
import org.apache.commons.pool2.ObjectPool;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;

@ThreadSafe
@ToString
public final class Conottle implements ConcurrentThrottler {
    private static final ExecutorService ADMIN_EXECUTOR_SERVICE = ForkJoinPool.commonPool();
    private static final int DEFAULT_MAX_ACTIVE_EXECUTORS = Integer.MAX_VALUE;
    private static final int DEFAULT_MIN_IDLE_EXECUTORS = 2;
    private static final int DEFAULT_THROTTLE = Runtime.getRuntime().availableProcessors();
    private static final Duration MIN_EVICTABLE_IDLE_TIME = Duration.ofMinutes(5);
    private static final Logger info = Logger.instance(Conottle.class).atInfo();
    private final ConcurrentMap<Object, ThrottledExecutor> activeExecutors;
    private final ObjectPool<ExecutorService> throttlingExecutorServicePool;

    private Conottle(Builder builder) {
        if (builder.throttleLimit < 0) {
            throw new IllegalArgumentException("throttle limit cannot be negative: " + builder.throttleLimit);
        }
        int throttleLimit = builder.throttleLimit;
        if (throttleLimit == 0) {
            throttleLimit = DEFAULT_THROTTLE;
        }
        int maxActiveExecutors = builder.activeClientLimit;
        if (maxActiveExecutors < 1) {
            maxActiveExecutors = DEFAULT_MAX_ACTIVE_EXECUTORS;
        }
        this.activeExecutors = new ConcurrentHashMap<>();
        this.throttlingExecutorServicePool =
                new GenericObjectPool<>(new ThrottlingExecutorServiceFactory(throttleLimit),
                        getThrottlingExecutorServicePoolConfig(maxActiveExecutors));
        info.log("constructed {}", this);
    }

    private static GenericObjectPoolConfig<ExecutorService> getThrottlingExecutorServicePoolConfig(int maxTotal) {
        GenericObjectPoolConfig<ExecutorService> throttlingExecutorServicePoolConfig = new GenericObjectPoolConfig<>();
        throttlingExecutorServicePoolConfig.setMaxTotal(maxTotal);
        throttlingExecutorServicePoolConfig.setMinIdle(DEFAULT_MIN_IDLE_EXECUTORS);
        throttlingExecutorServicePoolConfig.setMinEvictableIdleTime(MIN_EVICTABLE_IDLE_TIME);
        return throttlingExecutorServicePoolConfig;
    }

    @Override
    public Future<Void> execute(Runnable command, Object clientId) {
        return new MinimalFuture<>(activeExecutors.computeIfAbsent(clientId, getThrottledExecutor()).execute(command));
    }

    @Override
    public <V> Future<V> submit(Callable<V> task, Object clientId) {
        return new MinimalFuture<>(activeExecutors.computeIfAbsent(clientId, getThrottledExecutor()).submit(task));
    }

    private Function<Object, ThrottledExecutor> getThrottledExecutor() {
        return executorId -> {
            try {
                return new ThrottledExecutor(executorId, throttlingExecutorServicePool.borrowObject());
            } catch (Exception e) {
                throw new IllegalStateException("unable to borrow executor from pool " + throttlingExecutorServicePool,
                        e);
            }
        };
    }

    int countActiveExecutors() {
        return activeExecutors.size();
    }

    public static final class Builder {
        private int throttleLimit;
        private int activeClientLimit;

        public Builder activeClientLimit(int val) {
            this.activeClientLimit = val;
            return this;
        }

        public Builder throttleLimit(int val) {
            throttleLimit = val;
            return this;
        }

        public Conottle build() {
            return new Conottle(this);
        }
    }

    private static class UncheckedTaskCallException extends RuntimeException {
        public UncheckedTaskCallException(Exception e) {
            super(e);
        }
    }

    private static class MinimalFuture<V> implements Future<V> {
        @Delegate private final Future<V> delegate;

        public MinimalFuture(Future<V> delegate) {
            this.delegate = delegate;
        }
    }

    private static class ThrottlingExecutorServiceFactory extends BasePooledObjectFactory<ExecutorService> {
        private final Logger trace = Logger.instance(ThrottlingExecutorServiceFactory.class).atTrace();
        private final int throttleLimit;

        public ThrottlingExecutorServiceFactory(int throttleLimit) {
            this.throttleLimit = throttleLimit;
        }

        @Override
        public ExecutorService create() {
            return Executors.newFixedThreadPool(throttleLimit);
        }

        @Override
        public PooledObject<ExecutorService> wrap(ExecutorService executorService) {
            return new DefaultPooledObject<>(executorService);
        }

        @Override
        public void destroyObject(PooledObject<ExecutorService> pooledExecutorService, DestroyMode destroyMode)
                throws Exception {
            trace.log("destroying {} with {}...", pooledExecutorService, destroyMode);
            pooledExecutorService.getObject().shutdown();
            super.destroyObject(pooledExecutorService, destroyMode);
        }
    }

    @ToString
    private class ThrottledExecutor {
        private final Logger trace = Logger.instance(ThrottledExecutor.class).atTrace();
        private final Object executorId;
        private final AtomicInteger pendingTasks;
        private final ExecutorService throttlingExecutorService;

        public ThrottledExecutor(Object executorId, ExecutorService throttlingExecutorService) {
            this.executorId = executorId;
            this.throttlingExecutorService = throttlingExecutorService;
            this.pendingTasks = new AtomicInteger();
        }

        public Future<Void> execute(Runnable command) {
            pendingTasks.incrementAndGet();
            CompletableFuture<Void> resultFuture = CompletableFuture.runAsync(command, throttlingExecutorService);
            resultFuture.whenCompleteAsync(decrementPendingTasksAndMayDeactivateExecutor(), ADMIN_EXECUTOR_SERVICE);
            return resultFuture;
        }

        public <V> Future<V> submit(Callable<V> task) {
            pendingTasks.incrementAndGet();
            CompletableFuture<V> resultFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return task.call();
                } catch (Exception e) {
                    throw new UncheckedTaskCallException(e);
                }
            }, throttlingExecutorService);
            resultFuture.whenCompleteAsync(decrementPendingTasksAndMayDeactivateExecutor(), ADMIN_EXECUTOR_SERVICE);
            return resultFuture;
        }

        /**
         * Task count decrement and potential executor deactivation should be atomic.
         *
         * @param <V> type of task result
         * @return function to decrement task count and potentially deactivate executor
         */
        private <V> BiConsumer<V, Throwable> decrementPendingTasksAndMayDeactivateExecutor() {
            return (r, e) -> activeExecutors.compute(executorId, (sameId, self) -> {
                assert self == this;
                if (pendingTasks.decrementAndGet() == 0) {
                    trace.log("deactivating executor of {}...", executorId);
                    returnThrottlingExecutorServiceToPool();
                    return null;
                } else {
                    trace.log("retaining active executor of {}...", executorId);
                    return self;
                }
            });
        }

        private void returnThrottlingExecutorServiceToPool() {
            try {
                trace.log("returning {} to {}", throttlingExecutorService, throttlingExecutorServicePool);
                throttlingExecutorServicePool.returnObject(throttlingExecutorService);
            } catch (Exception ex) {
                trace.atWarn()
                        .log(ex,
                                "ignoring failure of returning {} to {}",
                                throttlingExecutorService,
                                throttlingExecutorServicePool);
            }
        }
    }
}
