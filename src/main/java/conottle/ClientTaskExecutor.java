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

import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * Implementation should provide throttle capability on concurrent tasks of each client, and optionally on total number
 * of clients serviced in parallel.
 */
public interface ClientTaskExecutor {
    /**
     * @param command
     *         {@link Runnable} command to run asynchronously. All such commands under the same {@code clientId} are run
     *         in parallel, albeit throttled at a maximum concurrency.
     * @param clientId
     *         A key representing a client whose tasks are throttled while running in parallel
     * @return {@link Future} holding the run status of the {@code command}
     */
    Future<Void> execute(Runnable command, Object clientId);

    /**
     * @param task
     *         {@link Callable} task to run asynchronously. All such tasks under the same {@code clientId} are run in
     *         parallel, albeit throttled at a maximum concurrency.
     * @param clientId
     *         A key representing a client whose tasks are throttled while running in parallel
     * @param <V>
     *         Type of the task result
     * @return {@link Future} representing the result of the {@code task}
     */
    <V> Future<V> submit(Callable<V> task, Object clientId);
}
