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
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.DestroyMode;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

/**
 * Creates pooled {@link PendingTaskCountingThrottleExecutor} instances that provide throttled async client task
 * executions. Each {@code PendingTaskCountingThrottleExecutor} instance throttles its client task concurrency at the
 * max capacity of the executor's backing thread pool.
 */
final class PooledExecutorFactory extends BasePooledObjectFactory<PendingWorkAwareExecutor> {
    private final boolean virtualThreading;

    private final int maxExecutorConcurrency;

    /**
     * @param virtualThreading
     *         if true, then use virtual threads to facilitate async operations.
     * @param maxExecutorConcurrency
     *         max concurrent threads of the {@link PendingWorkAwareExecutor} instance produced by this factory
     */
    PooledExecutorFactory(boolean virtualThreading, int maxExecutorConcurrency) {
        this.virtualThreading = virtualThreading;
        this.maxExecutorConcurrency = maxExecutorConcurrency;
    }

    @Override
    @NonNull
    public PendingWorkAwareExecutor create() {
        return virtualThreading ? new PendingTaskLimitingThrottleExecutor(maxExecutorConcurrency) :
                new PendingTaskCountingThrottleExecutor(maxExecutorConcurrency);
    }

    @Override
    @NonNull
    public PooledObject<PendingWorkAwareExecutor> wrap(PendingWorkAwareExecutor throttlingExecutor) {
        return new DefaultPooledObject<>(throttlingExecutor);
    }

    @Override
    public void destroyObject(PooledObject<PendingWorkAwareExecutor> pooledThrottlingExecutor, DestroyMode destroyMode)
            throws Exception {
        try {
            super.destroyObject(pooledThrottlingExecutor, destroyMode);
        } finally {
            pooledThrottlingExecutor.getObject().shutdown();
        }
    }
}
