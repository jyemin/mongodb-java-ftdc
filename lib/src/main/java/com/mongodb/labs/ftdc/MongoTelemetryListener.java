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
import com.mongodb.MongoCommandException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoSocketReadTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.connection.ClusterDescription;
import com.mongodb.connection.ClusterId;
import com.mongodb.connection.ClusterSettings;
import com.mongodb.connection.ConnectionPoolSettings;
import com.mongodb.connection.ServerDescription;
import com.mongodb.connection.SocketSettings;
import com.mongodb.event.ClusterClosedEvent;
import com.mongodb.event.ClusterDescriptionChangedEvent;
import com.mongodb.event.ClusterListener;
import com.mongodb.event.ClusterOpeningEvent;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import com.mongodb.event.ConnectionCheckOutFailedEvent;
import com.mongodb.event.ConnectionCheckOutStartedEvent;
import com.mongodb.event.ConnectionCheckedInEvent;
import com.mongodb.event.ConnectionCheckedOutEvent;
import com.mongodb.event.ConnectionClosedEvent;
import com.mongodb.event.ConnectionCreatedEvent;
import com.mongodb.event.ConnectionPoolClearedEvent;
import com.mongodb.event.ConnectionPoolClosedEvent;
import com.mongodb.event.ConnectionPoolCreatedEvent;
import com.mongodb.event.ConnectionPoolListener;
import com.mongodb.event.ConnectionReadyEvent;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.mongodb.connection.ClusterConnectionMode.SINGLE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

final class MongoTelemetryListener implements ClusterListener, CommandListener, ConnectionPoolListener {


    private final static class ConnectionPoolStatistics {
        private final AtomicLong clearedCount = new AtomicLong();
        private final AtomicLong checkOutStartedCount = new AtomicLong();
        private final AtomicLong checkedOutCount = new AtomicLong();
        private final AtomicLong checkOutFailedCount = new AtomicLong();
        private final AtomicLong checkedInCount = new AtomicLong();
        private final AtomicLong createdCount = new AtomicLong();
        private final AtomicLong readyCount = new AtomicLong();
        private final AtomicLong closedCount = new AtomicLong();
        private final AtomicLong checkOutsInProgressCount = new AtomicLong();
        private final AtomicLong operationsInProgressCount = new AtomicLong();
    }

    private final MongoTelemetryTracker telemetryTracker;
    private volatile boolean clientSettingsDocumentTracked;
    private final MongoClientSettings clientSettings;
    private volatile ClusterId clusterId;
    private volatile ClusterDescription clusterDescription;

    private final AtomicLong commandsInProgressCount = new AtomicLong();
    private final AtomicLong commandsCompletedCount = new AtomicLong();
    private final AtomicLong socketTimeoutCommandFailureCount = new AtomicLong();
    private final AtomicLong socketErrorCommandFailureCount = new AtomicLong();

    private final ConcurrentHashMap<Integer, AtomicLong> serverErrorCodeCountMap = new ConcurrentHashMap<>();
    private final AtomicLong commandGte0MsCount = new AtomicLong();
    private final AtomicLong commandGte10MsCount = new AtomicLong();
    private final AtomicLong commandGte100MsCount = new AtomicLong();
    private final AtomicLong commandGte1000MsCount = new AtomicLong();
    private final AtomicLong commandGte10000MsCount = new AtomicLong();
    private final AtomicLong commandGte100000MsCount = new AtomicLong();

    private final Map<ServerAddress, ConnectionPoolStatistics> connectionPoolStatisticsMap = new ConcurrentHashMap<>();

    public static void addToClientSettings(MongoTelemetryTracker telemetryTracker,
                                           MongoClientSettings.Builder clientSettingsBuilder) {
        MongoTelemetryListener telemetryListener = new MongoTelemetryListener(telemetryTracker,
                clientSettingsBuilder.build());
        clientSettingsBuilder.applyToClusterSettings(builder -> builder.addClusterListener(telemetryListener));
        clientSettingsBuilder.addCommandListener(telemetryListener);
        clientSettingsBuilder.applyToConnectionPoolSettings(builder -> builder.addConnectionPoolListener(telemetryListener));
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
        commandsInProgressCount.incrementAndGet();
    }

    @Override
    public void commandSucceeded(CommandSucceededEvent event) {
        commandCompleted(event.getElapsedTime(MILLISECONDS));
    }

    @Override
    public void commandFailed(CommandFailedEvent event) {
        commandCompleted(event.getElapsedTime(MILLISECONDS));
        if (event.getThrowable() instanceof MongoCommandException) {
            MongoCommandException commandException = (MongoCommandException) event.getThrowable();
            serverErrorCodeCountMap.computeIfAbsent(commandException.getErrorCode(), errorCode -> new AtomicLong()).incrementAndGet();
        } else if (event.getThrowable() instanceof MongoSocketReadTimeoutException) {
            socketTimeoutCommandFailureCount.incrementAndGet();
        } else if (event.getThrowable() instanceof MongoSocketException) {
            socketErrorCommandFailureCount.incrementAndGet();
        }

    }

    private void commandCompleted(long elapsedTimeMillis) {
        commandsInProgressCount.decrementAndGet();
        commandsCompletedCount.incrementAndGet();

        if (elapsedTimeMillis >= 1000000) {
            commandGte100000MsCount.incrementAndGet();
        } else if (elapsedTimeMillis >= 100000) {
            commandGte10000MsCount.incrementAndGet();
        } else if (elapsedTimeMillis >= 10000) {
            commandGte10000MsCount.incrementAndGet();
        } else if (elapsedTimeMillis >= 1000) {
            commandGte1000MsCount.incrementAndGet();
        } else if (elapsedTimeMillis >= 100) {
            commandGte100MsCount.incrementAndGet();
        } else if (elapsedTimeMillis >= 10) {
            commandGte10MsCount.incrementAndGet();
        } else {
            commandGte0MsCount.incrementAndGet();
        }
    }

    @Override
    public void connectionPoolCreated(ConnectionPoolCreatedEvent event) {
        connectionPoolStatisticsMap.put(event.getServerId().getAddress(), new ConnectionPoolStatistics());
    }

    @Override
    public void connectionPoolCleared(ConnectionPoolClearedEvent event) {
        ConnectionPoolStatistics statistics = connectionPoolStatisticsMap.get(event.getServerId().getAddress());
        statistics.clearedCount.incrementAndGet();
    }

    @Override
    public void connectionPoolClosed(ConnectionPoolClosedEvent event) {
        connectionPoolStatisticsMap.remove(event.getServerId().getAddress());
    }

    @Override
    public void connectionCheckOutStarted(ConnectionCheckOutStartedEvent event) {
        ConnectionPoolStatistics statistics = connectionPoolStatisticsMap.get(event.getServerId().getAddress());
        statistics.checkOutStartedCount.incrementAndGet();
        statistics.checkOutsInProgressCount.incrementAndGet();
    }

    @Override
    public void connectionCheckedOut(ConnectionCheckedOutEvent event) {
        ConnectionPoolStatistics statistics =
                connectionPoolStatisticsMap.get(event.getConnectionId().getServerId().getAddress());
        statistics.checkedOutCount.incrementAndGet();
        statistics.checkOutsInProgressCount.decrementAndGet();
        statistics.operationsInProgressCount.incrementAndGet();
    }

    @Override
    public void connectionCheckOutFailed(ConnectionCheckOutFailedEvent event) {
        ConnectionPoolStatistics statistics =
                connectionPoolStatisticsMap.get(event.getServerId().getAddress());
        statistics.checkOutFailedCount.incrementAndGet();
        statistics.checkOutsInProgressCount.decrementAndGet();
    }

    @Override
    public void connectionCheckedIn(ConnectionCheckedInEvent event) {
        ConnectionPoolStatistics statistics =
                connectionPoolStatisticsMap.get(event.getConnectionId().getServerId().getAddress());
        statistics.checkedInCount.incrementAndGet();
        statistics.operationsInProgressCount.incrementAndGet();
    }

    @Override
    public void connectionCreated(ConnectionCreatedEvent event) {
        ConnectionPoolStatistics statistics =
                connectionPoolStatisticsMap.get(event.getConnectionId().getServerId().getAddress());
        statistics.createdCount.incrementAndGet();
    }

    @Override
    public void connectionReady(ConnectionReadyEvent event) {
        ConnectionPoolStatistics statistics =
                connectionPoolStatisticsMap.get(event.getConnectionId().getServerId().getAddress());
        statistics.readyCount.incrementAndGet();
    }

    @Override
    public void connectionClosed(ConnectionClosedEvent event) {
        ConnectionPoolStatistics statistics =
                connectionPoolStatisticsMap.get(event.getConnectionId().getServerId().getAddress());
        statistics.closedCount.incrementAndGet();
    }

    BsonDocument asClientSettingsDocument() {
        if (clientSettingsDocumentTracked) {
            return null;
        }
        clientSettingsDocumentTracked = true;
        BsonDocument clientSettingsDocument = new BsonDocument();
        BsonString timestamp = new BsonString(ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT));
        clientSettingsDocument.append("timestamp", timestamp);
        clientSettingsDocument.append("type", new BsonInt32(1));
        clientSettingsDocument.append("clientId", new BsonString(clusterId.getValue()));
        BsonDocument settingsDocument = new BsonDocument();

        ClusterSettings clusterSettings = clientSettings.getClusterSettings();
        settingsDocument.append("directConnection", new BsonBoolean(clusterSettings.getMode() == SINGLE));
        if (clusterSettings.getSrvHost() != null) {
            settingsDocument.append("srvHost", new BsonString(clusterSettings.getSrvHost()));
        } else {
            settingsDocument.append("hosts", clusterSettings.getHosts()
                    .stream().map(serverAddress -> new BsonString(serverAddress.toString()))
                    .collect(BsonArray::new, BsonArray::add, BsonArray::addAll));
        }
        settingsDocument.append("requiredType", new BsonString(clusterSettings.getRequiredClusterType().toString()));
        if (clusterSettings.getRequiredReplicaSetName() != null) {
            settingsDocument.append("replicaSet", new BsonString(clusterSettings.getRequiredReplicaSetName()));
        }

        if (clientSettings.getApplicationName() != null) {
            settingsDocument.append("applicationName", new BsonString(clientSettings.getApplicationName()));
        }
        if (clientSettings.getCredential() != null) {
            settingsDocument.append("authenticationMechanism",
                    new BsonString(clientSettings.getCredential().getMechanism() == null
                            ? "SCRAM"
                            : clientSettings.getCredential().getMechanism()));
        }
        settingsDocument.append("serverSelectionTimeoutMS", new BsonInt64(clusterSettings.getServerSelectionTimeout(MILLISECONDS)));
        settingsDocument.append("localThresholdMS", new BsonInt64(clusterSettings.getLocalThreshold(MILLISECONDS)));
        settingsDocument.append("retryReads", new BsonBoolean(clientSettings.getRetryReads()));
        settingsDocument.append("retryWrites", new BsonBoolean(clientSettings.getRetryWrites()));
        settingsDocument.append("compressors", clientSettings.getCompressorList().stream()
                .map(mongoCompressor -> new BsonString(mongoCompressor.getName()))
                        .collect(BsonArray::new, BsonArray::add, BsonArray::addAll));
        settingsDocument.append("uuidRepresentation", new BsonString(clientSettings.getUuidRepresentation().toString()));

        ConnectionPoolSettings connectionPoolSettings = clientSettings.getConnectionPoolSettings();
        settingsDocument.append("maxPoolSize", new BsonInt32(connectionPoolSettings.getMaxSize()));
        settingsDocument.append("minPoolSize", new BsonInt32(connectionPoolSettings.getMinSize()));
        settingsDocument.append("maxIdleTimeMS", new BsonInt64(connectionPoolSettings.getMaxConnectionIdleTime(MILLISECONDS)));
        settingsDocument.append("waitQueueTimeoutMS", new BsonInt64(connectionPoolSettings.getMaxWaitTime(MILLISECONDS)));
        appendMaxConnecting(connectionPoolSettings, settingsDocument);

        SocketSettings socketSettings = clientSettings.getSocketSettings();
        settingsDocument.append("connectTimeoutMS", new BsonInt32(socketSettings.getConnectTimeout(MILLISECONDS)));
        settingsDocument.append("socketTimeoutMS", new BsonInt32(socketSettings.getReadTimeout(MILLISECONDS)));

        settingsDocument.append("tls", new BsonBoolean(clientSettings.getSslSettings().isEnabled()));

        clientSettingsDocument.append("settings", settingsDocument);
        return clientSettingsDocument;
    }

    private void appendMaxConnecting(ConnectionPoolSettings connectionPoolSettings, BsonDocument settingsDocument) {
        try {
            Method getMaxConnectingMethod = ConnectionPoolSettings.class.getMethod("getMaxConnecting");
            Integer maxConnecting = (Integer) getMaxConnectingMethod.invoke(connectionPoolSettings);
            settingsDocument.append("maxConnecting", new BsonInt32(maxConnecting));
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            // ignore
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
        appendConnectionPools(periodicDocument);
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

        commandsDocument.append("inProgress", new BsonInt64(commandsInProgressCount.get()));
        commandsDocument.append("completed", new BsonInt64(commandsCompletedCount.get()));
        commandsDocument.append("socketError", new BsonInt64(socketErrorCommandFailureCount.get()));
        commandsDocument.append("socketTimeout", new BsonInt64(socketTimeoutCommandFailureCount.get()));

        BsonDocument serverErrorResponsesDocument = new BsonDocument();
        for (Map.Entry<Integer, AtomicLong> entry : serverErrorCodeCountMap.entrySet()) {
            serverErrorResponsesDocument.append(Integer.toString(entry.getKey()), new BsonInt64(entry.getValue().get()));
        }

        commandsDocument.append("serverErrorResponses", serverErrorResponsesDocument);

        commandsDocument.append("gte0Millis", new BsonInt64(commandGte0MsCount.get()));
        commandsDocument.append("gte10Millis", new BsonInt64(commandGte10MsCount.get()));
        commandsDocument.append("gte100Millis", new BsonInt64(commandGte100MsCount.get()));
        commandsDocument.append("gte1000Millis", new BsonInt64(commandGte1000MsCount.get()));
        commandsDocument.append("gte10000Millis", new BsonInt64(commandGte10000MsCount.get()));
        commandsDocument.append("gte100000Millis", new BsonInt64(commandGte10000MsCount.get()));
        commandsDocument.append("gte1000000Millis", new BsonInt64(commandGte100000MsCount.get()));

        periodicDocument.append("commands", commandsDocument);
    }

    private void appendConnectionPools(BsonDocument periodicDocument) {
        BsonArray poolsArray = new BsonArray();

        for (Map.Entry<ServerAddress, ConnectionPoolStatistics> cur : connectionPoolStatisticsMap.entrySet()) {
            BsonDocument poolDocument = new BsonDocument();

            poolDocument.append("address", new BsonString(cur.getKey().toString()));
            poolDocument.append("checkOutsInProgress", new BsonInt64(cur.getValue().checkOutsInProgressCount.get()));
            poolDocument.append("operationsInProgress", new BsonInt64(cur.getValue().checkOutsInProgressCount.get()));

            poolDocument.append("ready", new BsonInt64(cur.getValue().readyCount.get()));
            poolDocument.append("cleared", new BsonInt64(cur.getValue().clearedCount.get()));
            poolDocument.append("opened", new BsonInt64(cur.getValue().createdCount.get()));
            poolDocument.append("closed", new BsonInt64(cur.getValue().closedCount.get()));
            poolDocument.append("checkOutStarted", new BsonInt64(cur.getValue().checkOutStartedCount.get()));
            poolDocument.append("checkOutFailed", new BsonInt64(cur.getValue().checkOutFailedCount.get()));
            poolDocument.append("checkedOut", new BsonInt64(cur.getValue().checkedOutCount.get()));
            poolDocument.append("checkedIn", new BsonInt64(cur.getValue().checkedInCount.get()));

            poolsArray.add(poolDocument);
        }

        periodicDocument.append("connectionPools", poolsArray);
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
