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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SampleUsageTest {
    private static final Duration MIN_TASK_DURATION = Duration.ofMillis(100);
    private static final Logger info = Logger.instance().atInfo();

    @Nested
    class submit {
        Conottle conottle = new Conottle.Builder().concurrentClientMaxTotal(100).singleClientMaxConcurrency(4).build();

        @Test
        void customized() {
            int clientCount = 2;
            int clientTaskCount = 10;
            List<Future<Task>> futures = new ArrayList<>(); // class Task implements Callable<Task>
            for (int c = 0; c < clientCount; c++) {
                String clientId = "clientId-" + (c + 1);
                for (int t = 0; t < clientTaskCount; t++) {
                    futures.add(this.conottle.submit(new Task(clientId + "-task-" + t, MIN_TASK_DURATION), clientId));
                }
            }

            assertEquals(clientCount,
                    this.conottle.countActiveExecutors(),
                    "should be 1:1 between a client and its executor");
            int taskTotal = futures.size();
            assertEquals(clientTaskCount * clientCount, taskTotal);
            info.log("none of the {} tasks will be done immediately", taskTotal);
            for (Future<Task> future : futures) {
                assertFalse(future.isDone());
            }
            info.log("all of the {} tasks will be done eventually", taskTotal);
            for (Future<Task> future : futures) {
                await().until(future::isDone);
            }
            info.log("no active executor lingers when all tasks complete");
            await().until(() -> this.conottle.countActiveExecutors() == 0);
        }

        @AfterEach
        void close() {
            this.conottle.close();
        }
    }
}