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

import lombok.NonNull;
import lombok.ToString;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * Not thread safe; needs to be synchronized.
 */
@NotThreadSafe
@ToString
final class PendingTaskCountingExecutor {
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
            throw new IllegalStateException("Cannot further decrement from pending task count: " + pendingTaskCount);
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
