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
import java.util.concurrent.Future;

import static java.time.temporal.ChronoUnit.MILLIS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConottleTest {
    private static final Logger info = Logger.instance(ConottleTest.class).atInfo();

    @Nested
    class submit {
        @Test
        void customizedConottle() {
            int maxActiveClients = 4;
            int throttleLimit = 3;
            Conottle conottle =
                    new Conottle.Builder().throttleLimit(throttleLimit).maxActiveClients(maxActiveClients).build();
            String clientId1 = "clientId1";
            String clientId2 = "clientId2";
            int totalTasksPerClient = 10;

            List<Future<Task>> futures = new ArrayList<>();
            for (int i = 0; i < totalTasksPerClient; i++) {
                futures.add(conottle.submit(new Task(clientId1 + "-task-" + i, Duration.of(100, MILLIS)), clientId1));
                futures.add(conottle.submit(new Task(clientId2 + "-task-" + i, Duration.of(100, MILLIS)), clientId2));
            }

            int totalClients = 2;
            assertEquals(totalClients, conottle.sizeOfActiveExecutors());
            for (Future<Task> future : futures) {
                info.log("{} will not get done right away", future);
                assertFalse(future.isDone());
            }
            for (Future<Task> future : futures) {
                info.log("but eventually {} will be done", future);
                await().until(future::isDone);
            }
            info.log("no executor lingers when all tasks complete");
            await().until(() -> conottle.sizeOfActiveExecutors() == 0);
        }
    }

    @Nested
    class execute {
        @Test
        void defaultBuilder() {
            Conottle conottle = new Conottle.Builder().build();
            String clientId1 = "clientId1";
            String clientId2 = "clientId2";
            int totalTasksPerClient = 10;

            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < totalTasksPerClient; i++) {
                futures.add(conottle.execute(new Task(clientId1 + "-task-" + i, Duration.of(100, MILLIS)), clientId1));
                futures.add(conottle.execute(new Task(clientId2 + "-task-" + i, Duration.of(100, MILLIS)), clientId2));
            }

            int totalClients = 2;
            assertEquals(totalClients, conottle.sizeOfActiveExecutors());
            for (Future<Void> future : futures) {
                info.log("{} will not get done right away", future);
                assertFalse(future.isDone());
            }
            for (Future<Void> future : futures) {
                info.log("but eventually {} will be done", future);
                await().until(future::isDone);
            }
            info.log("no executor lingers when all tasks complete");
            await().until(() -> conottle.sizeOfActiveExecutors() == 0);
        }
    }
}