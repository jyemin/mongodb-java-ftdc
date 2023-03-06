/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.labs.ftdc;

import com.mongodb.connection.ClusterId;
import org.bson.BsonDocument;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class MongoTelemetryTracker implements Closeable {

    private final ScheduledExecutorService writingService = Executors.newSingleThreadScheduledExecutor();

    private final Map<ClusterId, MongoTelemetryListener> telemetryListeners = new ConcurrentHashMap<>();
    private BufferedWriter writer;

    void init() throws IOException {
        Path path = Files.createFile(FileSystems.getDefault().getPath("ftdc"));
        writer = Files.newBufferedWriter(path);
        writingService.scheduleAtFixedRate(this::writeCurrentState, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        writingService.shutdown();
//        writer.flush();
//        writer.close();
    }

    void add(MongoTelemetryListener telemetryListener) {
        telemetryListeners.put(telemetryListener.getClusterId(), telemetryListener);
    }

    void remove(MongoTelemetryListener telemetryListener) {
        telemetryListeners.remove(telemetryListener.getClusterId());
    }

    private void writeCurrentState()  {
        try {
            for (MongoTelemetryListener cur : telemetryListeners.values()) {
                BsonDocument currentStateDocument = cur.asPeriodicDocument();
                writer.write(currentStateDocument.toJson());
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            // ignore for now
        }
    }
}
