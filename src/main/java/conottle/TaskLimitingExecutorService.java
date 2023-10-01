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

package conottle;

import lombok.NonNull;

import java.util.concurrent.*;

import static coco4j.CocoUtils.acquireInterruptibly;
import static coco4j.CocoUtils.supplyByUnchecked;

public class TaskLimitingExecutorService implements TaskThrottlingExecutorService {
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore semaphore;
    private final int permits;

    TaskLimitingExecutorService(int permits) {
        this.permits = permits;
        this.semaphore = new Semaphore(permits);
    }

    @Override
    public boolean noPendingWorkAfterTaskComplete() {
        semaphore.release();
        return semaphore.availablePermits() == permits;
    }

    @Override
    public @NonNull <V> CompletableFuture<V> submit(Callable<V> task) {
        acquireInterruptibly(semaphore);
        return CompletableFuture.supplyAsync(supplyByUnchecked(task), executorService);
    }

    @Override
    public void shutdown() {
        executorService.shutdown();
    }
}
