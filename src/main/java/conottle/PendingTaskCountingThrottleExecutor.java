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

import lombok.NonNull;
import lombok.ToString;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static coco4j.CocoUtils.callUnchecked;

/**
 * Not thread safe: Any and all non-private methods always should be externally synchronized while multithreading.
 */
@NotThreadSafe
@ToString
final class PendingTaskCountingThrottleExecutor implements PendingWorkAwareExecutor {
    /**
     * Thread pool that throttles the max concurrency of this executor
     */
    private final ExecutorService taskThreadPool;
    private int pendingTaskCount;

    /**
     * @param taskThreadPoolCapacity
     *         the capacity of backing thread pool facilitating the async executions of this executor.
     */
    PendingTaskCountingThrottleExecutor(int taskThreadPoolCapacity) {
        this.taskThreadPool = Executors.newFixedThreadPool(taskThreadPoolCapacity);
    }

    /**
     * To be called on completion of each submitted task. The pending task count after decrementing the current value by
     * one, accounting for the completion of one task. Within the synchronized context, a zero pending task count
     * unambiguously indicates no more in-flight task pending execution on this executor.
     *
     * @return true if there is no more pending tasks after the count is decremented to account for the previously
     *         completed task
     */
    @Override
    public boolean moreWorkPendingAfterTaskComplete() {
        if (pendingTaskCount <= 0) {
            throw new IllegalStateException("Cannot further decrement from pending task count: " + pendingTaskCount);
        }
        return --pendingTaskCount != 0;
    }

    @Override
    @NonNull
    public <V> CompletableFuture<V> submit(Callable<V> task) {
        return CompletableFuture.supplyAsync(() -> {
            pendingTaskCount++;
            return callUnchecked(task);
        }, taskThreadPool);
    }

    @Override
    public void shutdown() {
        taskThreadPool.shutdown();
    }
}
