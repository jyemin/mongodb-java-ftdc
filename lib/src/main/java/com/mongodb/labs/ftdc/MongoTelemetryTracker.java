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
import com.mongodb.internal.connection.ClientMetadataHelper;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.*;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

final class MongoTelemetryTracker implements Closeable {

    private final ScheduledExecutorService writingService =
            Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());

    private final Map<ClusterId, MongoTelemetryListener> telemetryListeners = new ConcurrentHashMap<>();
    private BufferedWriter writer;
    private long fileSize = 0;
    private String timestamp;
    private Path path;

    void schedule() {
        writingService.scheduleAtFixedRate(this::writeCurrentState, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        writingService.shutdown();
        try {
            writer.close();
            writer = null;
            Files.move(path, FileSystems.getDefault().getPath("diagnostics.data", "metrics." + timestamp),
                    REPLACE_EXISTING);
        } catch (IOException e) {
            // ignore
        }
    }

    void add(MongoTelemetryListener telemetryListener) {
        telemetryListeners.put(telemetryListener.getClusterId(), telemetryListener);
    }

    void remove(MongoTelemetryListener telemetryListener) {
        telemetryListeners.remove(telemetryListener.getClusterId());
    }

    private void writeCurrentState() {
        try {
            initFiles();
            for (MongoTelemetryListener cur : telemetryListeners.values()) {
                rotateMetricsFile();
                BsonDocument currentStateDocument = cur.asPeriodicDocument();
                writeDocument(currentStateDocument);
            }
        } catch (IOException e) {
            close();
        }
    }

    private void writeDocument(BsonDocument currentStateDocument) throws IOException {
        String jsonString = currentStateDocument.toJson();
        writer.write(jsonString);
        writer.newLine();
        writer.flush();
        fileSize += jsonString.length() + 1;  // assume all ASCII
    }

    private void initFiles() throws IOException {
        if (writer != null) {
            return;
        }
        Path directory = FileSystems.getDefault().getPath("diagnostics.data");
        path = FileSystems.getDefault().getPath("diagnostics.data", "metrics.interim");
        if (Files.notExists(directory)) {
            Files.createDirectory(directory);
        }
        Files.deleteIfExists(path);
        writer = Files.newBufferedWriter(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);

        timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
        BsonDocument frontMatterDocument = new BsonDocument();
        frontMatterDocument.append("timestamp", new BsonString(timestamp));
        frontMatterDocument.append("type", new BsonInt32(0));
        frontMatterDocument.append("metadata", ClientMetadataHelper.CLIENT_METADATA_DOCUMENT); // TODO: internal package
        writeDocument(frontMatterDocument);
        Files.copy(path, FileSystems.getDefault().getPath("diagnostics.data", "metrics." + timestamp));
    }

    private void rotateMetricsFile() {
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(final Runnable runnable) {
            Thread thread = new Thread(runnable, "MongoTelemetryTracker");
            thread.setDaemon(true);
            return thread;
        }
    }

}
