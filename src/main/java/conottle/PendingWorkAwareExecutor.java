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

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Async task executor. Implementations reports if there are more tasks pending when informed of each task's completion,
 * and can provide throttling on max number of tasks being executed in parallel. Caller of this executor can decide
 * whether to keep or discard this executor based on reported pending work status.
 */
interface PendingWorkAwareExecutor {
    /**
     * Intended to be called after each task is completed by this service.
     *
     * @return true if this executor has more pending tasks after the task whose completion triggered this method
     *         invocation
     */
    boolean moreWorkPendingAfterTaskComplete();

    @NonNull <V> CompletableFuture<V> submit(Callable<V> task);

    void shutdown();
}
