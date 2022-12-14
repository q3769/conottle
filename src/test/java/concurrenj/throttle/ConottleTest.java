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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class ConottleTest {
    private static final Duration MIN_TASK_DURATION = Duration.ofMillis(100);
    private static final Logger info = Logger.instance(ConottleTest.class).atInfo();

    private static void testExecute(Conottle conottle) {
        String clientId1 = "clientId1";
        String clientId2 = "clientId2";
        int clientTaskTotal = 10;

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < clientTaskTotal; i++) {
            futures.add(conottle.execute(new Task(clientId1 + "-task-" + i, MIN_TASK_DURATION), clientId1));
            futures.add(conottle.execute(new Task(clientId2 + "-task-" + i, MIN_TASK_DURATION), clientId2));
        }

        int clientTotal = 2;
        assertEquals(clientTotal, conottle.countActiveExecutors(), "should be 1:1 between a client and its executor");
        int taskTotal = futures.size();
        info.log("none of {} tasks will be done immediately", taskTotal);
        for (Future<Void> future : futures) {
            assertFalse(future.isDone());
        }
        info.log("all of {} tasks will be done eventually", taskTotal);
        for (Future<Void> future : futures) {
            await().until(future::isDone);
        }
        info.log("no active executor lingers when all tasks complete");
        await().until(() -> conottle.countActiveExecutors() == 0);
    }

    @Nested
    class submit {
        @Test
        void customized() throws ExecutionException, InterruptedException {
            Conottle conottle = new Conottle.Builder().throttleLimit(4).concurrentClientLimit(50).build();
            String clientId1 = "clientId1";
            String clientId2 = "clientId2";
            int clientTaskTotal = 10;

            List<Future<Task>> futures = new ArrayList<>(); // class Task implements Callable<Task>
            for (int i = 0; i < clientTaskTotal; i++) {
                futures.add(conottle.submit(new Task(clientId1 + "-task-" + i, MIN_TASK_DURATION), clientId1));
                futures.add(conottle.submit(new Task(clientId2 + "-task-" + i, MIN_TASK_DURATION), clientId2));
            }

            int clientTotal = 2;
            assertEquals(clientTotal,
                    conottle.countActiveExecutors(),
                    "should be 1:1 between a client and its executor");
            int taskTotal = futures.size();
            info.log("none of {} tasks will be done immediately", taskTotal);
            for (Future<Task> future : futures) {
                assertFalse(future.isDone());
            }
            info.log("all of {} tasks will be done eventually", taskTotal);
            for (Future<Task> future : futures) {
                await().until(future::isDone);
                assertTrue(future.get().isComplete());
            }
            info.log("no active executor lingers when all tasks complete");
            await().until(() -> conottle.countActiveExecutors() == 0);
        }
    }

    @Nested
    class execute {
        @Test
        void allDefault() {
            testExecute(new Conottle.Builder().build());
        }

        @Test
        void customizedThrottleLimit() {
            testExecute(new Conottle.Builder().throttleLimit(3).build());
        }

        @Test
        void customizedMaxActiveClients() {
            testExecute(new Conottle.Builder().concurrentClientLimit(4).build());
        }

        @Test
        void noNegativeThrottleLimit() {
            Conottle.Builder builder = new Conottle.Builder().throttleLimit(-1);

            assertThrows(IllegalArgumentException.class, builder::build);
        }
    }
}