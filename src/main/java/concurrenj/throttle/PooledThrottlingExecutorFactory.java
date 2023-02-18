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
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.DestroyMode;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

import java.util.concurrent.Executors;

/**
 * Creates pooled {@link ThrottlingExecutor} instances to facilitate async client task executions.
 * The max concurrent threads of each {@code ThrottlingExecutor} instance will be the throttle limit
 * of each client.
 */
final class PooledThrottlingExecutorFactory extends BasePooledObjectFactory<ThrottlingExecutor> {
    private final int maxExecutorServiceConcurrency;

    /**
     * @param maxExecutorServiceConcurrency max concurrent threads of the {@link ThrottlingExecutor}
     *                                      instance produced by this factory
     */
    public PooledThrottlingExecutorFactory(int maxExecutorServiceConcurrency) {
        this.maxExecutorServiceConcurrency = maxExecutorServiceConcurrency;
    }

    @Override
    @NonNull
    public ThrottlingExecutor create() {
        return new ThrottlingExecutor(Executors.newFixedThreadPool(maxExecutorServiceConcurrency));
    }

    @Override
    @NonNull
    public PooledObject<ThrottlingExecutor> wrap(ThrottlingExecutor throttlingExecutor) {
        return new DefaultPooledObject<>(throttlingExecutor);
    }

    @Override
    public void destroyObject(PooledObject<ThrottlingExecutor> pooledThrottlingExecutor,
            DestroyMode destroyMode) throws Exception {
        try {
            super.destroyObject(pooledThrottlingExecutor, destroyMode);
        } finally {
            pooledThrottlingExecutor.getObject().getThrottlingExecutorService().shutdown();
        }
    }
}
