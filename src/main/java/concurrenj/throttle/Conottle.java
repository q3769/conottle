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
import lombok.NonNull;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.DestroyMode;
import org.apache.commons.pool2.ObjectPool;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;

public final class Conottle implements ConcurrentThrottle {
    private static final int DEFAULT_MAX_ACTIVE_EXECUTORS = Integer.MAX_VALUE;
    private static final int DEFAULT_MIN_IDLE_EXECUTORS = 2;
    private static final int DEFAULT_THROTTLE = Runtime.getRuntime().availableProcessors();
    private static final ForkJoinPool EXECUTOR_ADMIN_THREAD_POOL = ForkJoinPool.commonPool();
    private static final Duration MIN_EVICTABLE_IDLE_TIME = Duration.ofMinutes(5);
    private final ConcurrentMap<Object, ActiveExecutor> activeExecutors;

    private final ObjectPool<ExecutorService> executorPool;

    private Conottle(Builder builder) {
        if (builder.throttleLimit < 0) {
            throw new IllegalArgumentException("throttle limit cannot be negative: " + builder.throttleLimit);
        }
        int throttleLimit = builder.throttleLimit;
        if (throttleLimit == 0) {
            throttleLimit = DEFAULT_THROTTLE;
        }
        int maxActiveExecutors = builder.maxActiveExecutors;
        if (maxActiveExecutors < 1) {
            maxActiveExecutors = DEFAULT_MAX_ACTIVE_EXECUTORS;
        }
        activeExecutors = new ConcurrentHashMap<>();
        GenericObjectPoolConfig<ExecutorService> executorPoolConfig = new GenericObjectPoolConfig<>();
        executorPoolConfig.setMaxTotal(maxActiveExecutors);
        executorPoolConfig.setMinIdle(DEFAULT_MIN_IDLE_EXECUTORS);
        executorPoolConfig.setMinEvictableIdleTime(MIN_EVICTABLE_IDLE_TIME);
        executorPool = new GenericObjectPool<>(new ExecutionThreadPoolFactory(throttleLimit), executorPoolConfig);
    }

    @Override
    public Future<Void> execute(Runnable command, Object throttleId) {
        return new MinimalFuture<>(activeExecutors.computeIfAbsent(throttleId, getPooledExecutor()).execute(command));
    }

    @Override
    public <V> Future<V> submit(Callable<V> task, Object throttleId) {
        return new MinimalFuture<>(activeExecutors.computeIfAbsent(throttleId, getPooledExecutor()).submit(task));
    }

    private Function<Object, ActiveExecutor> getPooledExecutor() {
        return key -> {
            try {
                return new ActiveExecutor(key, executorPool.borrowObject());
            } catch (Exception e) {
                throw new IllegalStateException("unable to borrow executor from pool " + executorPool, e);
            }
        };
    }

    int sizeOfActiveExecutors() {
        return this.activeExecutors.size();
    }

    public static final class Builder {
        private int throttleLimit;
        private int maxActiveExecutors;

        public Builder maxActiveExecutors(int val) {
            this.maxActiveExecutors = val;
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

    private static class UncheckedCallException extends RuntimeException {
        public UncheckedCallException(Exception e) {
            super(e);
        }
    }

    private static class MinimalFuture<V> implements Future<V> {
        private final Future<V> delegate;

        public MinimalFuture(Future<V> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return delegate.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            return delegate.get();
        }

        @Override
        public V get(long timeout, @NonNull TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return delegate.get(timeout, unit);
        }
    }

    private static class ExecutionThreadPoolFactory extends BasePooledObjectFactory<ExecutorService> {
        private final Logger trace = Logger.instance(ExecutionThreadPoolFactory.class).atTrace();
        private final int throttleLimit;

        public ExecutionThreadPoolFactory(int throttleLimit) {
            this.throttleLimit = throttleLimit;
        }

        @Override
        public ExecutorService create() {
            return Executors.newFixedThreadPool(this.throttleLimit);
        }

        @Override
        public PooledObject<ExecutorService> wrap(ExecutorService executorService) {
            return new DefaultPooledObject<>(executorService);
        }

        @Override
        public void destroyObject(PooledObject<ExecutorService> p, DestroyMode destroyMode) throws Exception {
            trace.log("destroying pooled executor {} with mode {}...", p, destroyMode);
            p.getObject().shutdown();
            super.destroyObject(p, destroyMode);
        }
    }

    private class ActiveExecutor {
        private final Logger trace = Logger.instance(ActiveExecutor.class).atTrace();
        private final Object throttleId;
        private final AtomicInteger pendingTasks;
        private final ExecutorService threadPool;

        public ActiveExecutor(Object throttleId, ExecutorService threadPool) {
            this.throttleId = throttleId;
            this.threadPool = threadPool;
            this.pendingTasks = new AtomicInteger();
        }

        public Future<Void> execute(Runnable command) {
            pendingTasks.incrementAndGet();
            CompletableFuture<Void> resultFuture = CompletableFuture.runAsync(command, this.threadPool);
            resultFuture.whenCompleteAsync(decrementPendingTasksAndMayDeactivateExecutor(), EXECUTOR_ADMIN_THREAD_POOL);
            return resultFuture;
        }

        public <V> Future<V> submit(Callable<V> task) {
            pendingTasks.incrementAndGet();
            CompletableFuture<V> resultFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return task.call();
                } catch (Exception e) {
                    throw new UncheckedCallException(e);
                }
            }, this.threadPool);
            resultFuture.whenCompleteAsync(decrementPendingTasksAndMayDeactivateExecutor(), EXECUTOR_ADMIN_THREAD_POOL);
            return resultFuture;
        }

        private <V> BiConsumer<V, Throwable> decrementPendingTasksAndMayDeactivateExecutor() {
            return (r, e) -> {
                if (pendingTasks.decrementAndGet() == 0) {
                    trace.log("deactivating executor for throttle id {}...", this.throttleId);
                    ExecutorService toReturn = activeExecutors.remove(this.throttleId).threadPool;
                    try {
                        executorPool.returnObject(toReturn);
                    } catch (Exception ex) {
                        trace.atWarn()
                                .log(ex, "ignoring failure to return executor {} to pool {}", toReturn, executorPool);
                    }
                }
            };
        }
    }
}
