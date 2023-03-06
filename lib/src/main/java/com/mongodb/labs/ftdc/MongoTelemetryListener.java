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

import com.mongodb.MongoClientSettings;
import com.mongodb.connection.ClusterDescription;
import com.mongodb.connection.ClusterId;
import com.mongodb.connection.ServerDescription;
import com.mongodb.event.*;
import org.bson.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class MongoTelemetryListener implements ClusterListener, CommandListener {

    private final MongoTelemetryTracker telemetryTracker;
    private final MongoClientSettings clientSettings;
    private volatile ClusterId clusterId;
    private volatile ClusterDescription clusterDescription;

    private final AtomicLong commandsInProgress = new AtomicLong();
    private final AtomicLong commandGte0Ms = new AtomicLong();
    private final AtomicLong commandGte10Ms = new AtomicLong();
    private final AtomicLong commandGte100Ms = new AtomicLong();
    private final AtomicLong commandGte1000Ms = new AtomicLong();
    private final AtomicLong commandGte10000Ms = new AtomicLong();
    private final AtomicLong commandGte100000Ms = new AtomicLong();

    public static void addToClientSettings(MongoTelemetryTracker telemetryTracker,
                                           MongoClientSettings.Builder clientSettingsBuilder) {
        MongoTelemetryListener telemetryListener = new MongoTelemetryListener(telemetryTracker, clientSettingsBuilder.build());
        clientSettingsBuilder.applyToClusterSettings(builder -> builder.addClusterListener(telemetryListener));
        clientSettingsBuilder.addCommandListener(telemetryListener);
    }

    private MongoTelemetryListener(MongoTelemetryTracker telemetryTracker, MongoClientSettings clientSettings) {
        this.telemetryTracker = telemetryTracker;
        this.clientSettings = clientSettings;
    }

    public ClusterId getClusterId() {
        return clusterId;
    }

    @Override
    public void clusterOpening(ClusterOpeningEvent event) {
        clusterId = event.getClusterId();
        telemetryTracker.add(this);
    }

    @Override
    public void clusterClosed(ClusterClosedEvent event) {
        telemetryTracker.remove(this);
    }

    @Override
    public void clusterDescriptionChanged(ClusterDescriptionChangedEvent event) {
        clusterDescription = event.getNewDescription();
    }

    @Override
    public void commandStarted(CommandStartedEvent event) {
        commandsInProgress.incrementAndGet();
    }

    @Override
    public void commandSucceeded(CommandSucceededEvent event) {
        commandCompleted(event.getElapsedTime(TimeUnit.MILLISECONDS));
    }

    @Override
    public void commandFailed(CommandFailedEvent event) {
        commandCompleted(event.getElapsedTime(TimeUnit.MILLISECONDS));
    }

    private void commandCompleted(long elapsedTimeMillis) {
        commandsInProgress.decrementAndGet();

        if (elapsedTimeMillis >= 1000000) {
            commandGte100000Ms.incrementAndGet();
        } else if (elapsedTimeMillis >= 100000) {
            commandGte10000Ms.incrementAndGet();
        } else if (elapsedTimeMillis >= 10000) {
            commandGte10000Ms.incrementAndGet();
        } else if (elapsedTimeMillis >= 1000) {
            commandGte1000Ms.incrementAndGet();
        } else if (elapsedTimeMillis >= 100) {
            commandGte100Ms.incrementAndGet();
        } else if (elapsedTimeMillis >= 10) {
            commandGte10Ms.incrementAndGet();
        } else {
            commandGte0Ms.incrementAndGet();
        }
    }

    BsonDocument asPeriodicDocument() {
        BsonString timestamp = new BsonString(ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT));
        BsonDocument periodicDocument = new BsonDocument();
        periodicDocument.append("timestamp", timestamp);
        periodicDocument.append("type", new BsonInt32(2));
        periodicDocument.append("clientId", new BsonString(clusterId.getValue()));

        appendClusterDescription(periodicDocument);
        appendCommands(periodicDocument);
        return periodicDocument;
    }

    private void appendClusterDescription(BsonDocument periodicDocument) {
        ClusterDescription curDescription = clusterDescription;
        if (curDescription != null) {
            BsonDocument topologyDocument = new BsonDocument();
            topologyDocument.append("type", new BsonString(curDescription.getType().toString()));

            BsonArray serverArray = new BsonArray();
            for (ServerDescription curServerDescription : curDescription.getServerDescriptions()) {
                BsonDocument serverDocument = new BsonDocument();

                serverDocument.append("address", new BsonString(curServerDescription.getAddress().toString()));
                serverDocument.append("state", new BsonString(curServerDescription.getState().toString()));
                serverDocument.append("type", new BsonString(curServerDescription.getType().toString()));
                serverDocument.append("rttMillis",
                        new BsonDouble(nanosToMilliseconds(curServerDescription.getRoundTripTimeNanos())));

                serverArray.add(serverDocument);
            }

            topologyDocument.append("servers", serverArray);
            periodicDocument.append("topology", topologyDocument);
        }
    }

    private void appendCommands(BsonDocument periodicDocument) {
        BsonDocument commandsDocument = new BsonDocument();

        commandsDocument.append("inProgress", new BsonInt64(commandsInProgress.get()));

        commandsDocument.append("gte0Millis", new BsonInt64(commandGte0Ms.get()));
        commandsDocument.append("gte10Millis", new BsonInt64(commandGte10Ms.get()));
        commandsDocument.append("gte100Millis", new BsonInt64(commandGte100Ms.get()));
        commandsDocument.append("gte1000Millis", new BsonInt64(commandGte1000Ms.get()));
        commandsDocument.append("gte10000Millis", new BsonInt64(commandGte10000Ms.get()));
        commandsDocument.append("gte100000Millis", new BsonInt64(commandGte10000Ms.get()));
        commandsDocument.append("gte1000000Millis", new BsonInt64(commandGte100000Ms.get()));

        periodicDocument.append("commands", commandsDocument);
    }

    private double nanosToMilliseconds(final long nanos) {
        return round(nanos / 1000000.0, 2);
    }

    public static double round(double value, int places) {
        BigDecimal bigDecimal = BigDecimal.valueOf(value);
        bigDecimal = bigDecimal.setScale(places, RoundingMode.HALF_UP);
        return bigDecimal.doubleValue();
    }

}
