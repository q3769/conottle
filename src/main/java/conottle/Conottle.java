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

import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.*;

/**
 * Provides throttling on concurrent tasks per client, and on total number of clients serviced concurrently.
 */
@ThreadSafe
@ToString
public final class Conottle implements ClientTaskExecutor, AutoCloseable {
    private static final ExecutorService ADMIN_EXECUTOR_SERVICE = Executors.newCachedThreadPool();
    private static final int DEFAULT_CONCURRENT_CLIENT_MAX_TOTAL =
            Math.max(16, Runtime.getRuntime().availableProcessors());
    private static final int DEFAULT_SINGLE_CLIENT_MAX_CONCURRENCY =
            Math.max(16, Runtime.getRuntime().availableProcessors());
    private static final Logger logger = Logger.instance();
    private final ConcurrentMap<Object, ThrottlingExecutor> activeThrottlingExecutors;
    private final Semaphore clientTotalQuota;
    private final int singleClientConcurrency;

    private Conottle(@NonNull Builder builder) {
        this.activeThrottlingExecutors = new ConcurrentHashMap<>(builder.concurrentClientMaxTotal);
        singleClientConcurrency = builder.singleClientMaxConcurrency;
        clientTotalQuota = new Semaphore(builder.concurrentClientMaxTotal);
        logger.atTrace().log("Success constructing: {}", this);
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
            ThrottlingExecutor executor = (presentExecutor == null) ? acquireClientExecutor() : presentExecutor;
            taskCompletableFutureHolder.setCompletableFuture(executor.incrementPendingTaskCountAndSubmit(task));
            return executor;
        });
        CompletableFuture<V> taskCompletableFuture = taskCompletableFutureHolder.getCompletableFuture();
        CompletableFuture<V> copy = taskCompletableFuture.thenApply(r -> r);
        taskCompletableFuture.whenCompleteAsync((r, e) -> activeThrottlingExecutors.computeIfPresent(clientId,
                (k, checkedExecutor) -> {
                    if (checkedExecutor.decrementAndGetPendingTaskCount() == 0) {
                        clientTotalQuota.release();
                        checkedExecutor.shutdown();
                        return null;
                    }
                    return checkedExecutor;
                }), ADMIN_EXECUTOR_SERVICE);
        return copy;
    }

    @Override
    public void close() {
        activeThrottlingExecutors.values().parallelStream().forEach(ThrottlingExecutor::shutdown);
    }

    int countActiveExecutors() {
        return activeThrottlingExecutors.size();
    }

    private ThrottlingExecutor acquireClientExecutor() {
        try {
            clientTotalQuota.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new ThrottlingExecutor(Executors.newFixedThreadPool(singleClientConcurrency));
    }

    /**
     * Builder that can customize throttle limit on per-client concurrent tasks, and/or limit on total number of clients
     * concurrently serviced
     */
    @NoArgsConstructor
    public static final class Builder {
        private int concurrentClientMaxTotal = DEFAULT_CONCURRENT_CLIENT_MAX_TOTAL;
        private int singleClientMaxConcurrency = DEFAULT_SINGLE_CLIENT_MAX_CONCURRENCY;

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
        public Builder concurrentClientMaxTotal(int val) {
            if (val > 0) {
                concurrentClientMaxTotal = val;
            }
            return this;
        }

        /**
         * @param val
         *         max number of tasks that can be concurrently executed per each client
         * @return the name builder instance
         */
        public Builder singleClientMaxConcurrency(int val) {
            if (val > 0) {
                singleClientMaxConcurrency = val;
            }
            return this;
        }
    }

    @Data
    private static final class CompletableFutureHolder<V> {
        private CompletableFuture<V> completableFuture;
    }
}
