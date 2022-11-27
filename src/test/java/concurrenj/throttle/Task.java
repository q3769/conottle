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
import lombok.Getter;
import lombok.ToString;

import java.time.Duration;
import java.util.concurrent.Callable;

import static org.awaitility.Awaitility.await;

@ToString
@Getter
public class Task implements Callable<Task>, Runnable {
    private static final Logger trace = Logger.instance(Task.class).atTrace();
    private final Object taskId;
    private final Duration minDuration;
    private final Duration pollInterval;
    private long startTimeMillis;
    private long endTimeMillis;
    private String executionThreadName;
    private boolean complete;

    public Task(Object taskId, Duration minDuration) {
        this.taskId = taskId;
        this.minDuration = minDuration;
        this.pollInterval = Duration.ofMillis(minDuration.toMillis() + 1);
    }

    @Override
    public Task call() {
        this.startTimeMillis = System.currentTimeMillis();
        this.executionThreadName = Thread.currentThread().getName();
        trace.log("{} started to run by {}", this, this.executionThreadName);
        this.complete = true;
        await().pollInterval(pollInterval).atLeast(minDuration).until(this::isComplete);
        this.endTimeMillis = System.currentTimeMillis();
        trace.log("{} completed in {}", this, this.getActualDuration());
        return this;
    }

    @Override
    public void run() {
        this.call();
    }

    public Duration getActualDuration() {
        if (!isComplete()) {
            throw new IllegalStateException(this + " is not complete");
        }
        return Duration.ofMillis(this.endTimeMillis - this.startTimeMillis);
    }
}
