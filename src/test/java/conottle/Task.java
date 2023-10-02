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

import coco4j.CocoUtils;
import elf4j.Logger;
import lombok.Getter;
import lombok.ToString;

import java.time.Duration;
import java.util.concurrent.Callable;

@ToString
@Getter
public class Task implements Callable<Task>, Runnable {
    private static final Logger trace = Logger.instance().atTrace();
    private final Object taskId;
    private String executionThreadName;
    private final Duration minDuration;
    private Long startTimeMillis;
    private Long endTimeMillis;

    public Task(Object taskId, Duration minDuration) {
        this.taskId = taskId;
        this.minDuration = minDuration;
    }

    @Override
    public Task call() {
        this.startTimeMillis = System.currentTimeMillis();
        this.executionThreadName = Thread.currentThread().getName();
        trace.log("started {}", this);
        CocoUtils.sleepInterruptibly(minDuration);
        this.endTimeMillis = System.currentTimeMillis();
        trace.log("completed {} in {}", this, this.getActualDuration());
        return this;
    }

    public Duration getActualDuration() {
        if (!isComplete()) {
            throw new IllegalStateException(this + " is not complete");
        }
        return Duration.ofMillis(this.endTimeMillis - this.startTimeMillis);
    }

    @Override
    public void run() {
        this.call();
    }

    private boolean isComplete() {
        return this.endTimeMillis != null;
    }
}
