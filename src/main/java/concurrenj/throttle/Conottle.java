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
public final class Conottle implements ClientTaskExecutor {
    private static final ExecutorService ADMIN_EXECUTOR_SERVICE = Executors.newCachedThreadPool();
    private static final int DEFAULT_MAX_CONCURRENT_CLIENT_TOTAL = Integer.MAX_VALUE;
    private static final int DEFAULT_MAX_SINGLE_CLIENT_CONCURRENCY = Runtime.getRuntime().availableProcessors();
    private static final Logger logger = Logger.instance();
    private final ConcurrentMap<Object, ThrottlingExecutor> activeThrottlingExecutors;
    private final ObjectPool<ThrottlingExecutor> throttlingExecutorPool;

    private Conottle(@NonNull Builder builder) {
        this.activeThrottlingExecutors = new ConcurrentHashMap<>();
        this.throttlingExecutorPool =
                new GenericObjectPool<>(new PooledThrottlingExecutorFactory(builder.maxSingleClientConcurrency),
                        getThrottlingExecutorPoolConfig(builder.maxConcurrentClientTotal));
        logger.atTrace().log("Success constructing: {}", this);
    }

    @NonNull
    private static GenericObjectPoolConfig<ThrottlingExecutor> getThrottlingExecutorPoolConfig(int maxThrottlingExecutorPoolCapacity) {
        GenericObjectPoolConfig<ThrottlingExecutor> throttlingExecutorPoolConfig = new GenericObjectPoolConfig<>();
        throttlingExecutorPoolConfig.setMaxTotal(maxThrottlingExecutorPoolCapacity);
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
            ThrottlingExecutor executor = (presentExecutor == null) ? borrowFromPool() : presentExecutor;
            taskCompletableFutureHolder.setCompletableFuture(executor.incrementPendingTaskCountAndSubmit(task));
            return executor;
        });
        CompletableFuture<V> taskCompletableFuture = taskCompletableFutureHolder.getCompletableFuture();
        taskCompletableFuture.whenCompleteAsync((r, e) -> activeThrottlingExecutors.computeIfPresent(clientId,
                (k, checkedExecutor) -> {
                    if (checkedExecutor.decrementAndGetPendingTaskCount() == 0) {
                        returnToPool(checkedExecutor);
                        return null;
                    }
                    return checkedExecutor;
                }), ADMIN_EXECUTOR_SERVICE);
        return taskCompletableFuture.thenApply(r -> r);
    }

    int countActiveExecutors() {
        return activeThrottlingExecutors.size();
    }

    private ThrottlingExecutor borrowFromPool() {
        try {
            return throttlingExecutorPool.borrowObject();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to borrow executor from pool " + throttlingExecutorPool, e);
        }
    }

    private void returnToPool(ThrottlingExecutor throttlingExecutor) {
        ADMIN_EXECUTOR_SERVICE.submit(() -> {
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
        private int maxConcurrentClientTotal = DEFAULT_MAX_CONCURRENT_CLIENT_TOTAL;
        private int maxSingleClientConcurrency = DEFAULT_MAX_SINGLE_CLIENT_CONCURRENCY;

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
        public Builder maxConcurrentClientTotal(int val) {
            if (val > 0) {
                maxConcurrentClientTotal = val;
            }
            return this;
        }

        /**
         * @param val max number of tasks that can be concurrently executed per each client
         * @return the name builder instance
         */
        public Builder maxSingleClientConcurrency(int val) {
            if (val > 0) {
                maxSingleClientConcurrency = val;
            }
            return this;
        }
    }

    @Data
    private static final class CompletableFutureHolder<V> {
        private CompletableFuture<V> completableFuture;
    }
}
