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
import com.mongodb.event.ClusterClosedEvent;
import com.mongodb.event.ClusterDescriptionChangedEvent;
import com.mongodb.event.ClusterListener;
import com.mongodb.event.ClusterOpeningEvent;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

final class MongoTelemetryListener implements ClusterListener {

    private final MongoTelemetryTracker telemetryTracker;
    private final MongoClientSettings clientSettings;
    private volatile ClusterId clusterId;
    private volatile ClusterDescription clusterDescription;

    public static void addToClientSettings(MongoTelemetryTracker telemetryTracker,
                                           MongoClientSettings.Builder clientSettingsBuilder) {
        MongoTelemetryListener telemetryListener = new MongoTelemetryListener(telemetryTracker, clientSettingsBuilder.build());
        clientSettingsBuilder.applyToClusterSettings(builder -> builder.addClusterListener(telemetryListener));
    }

    private MongoTelemetryListener(MongoTelemetryTracker telemetryTracker, MongoClientSettings clientSettings) {
        this.telemetryTracker = telemetryTracker;
        this.clientSettings = clientSettings;
    }

    public MongoClientSettings getClientSettings() {
        return clientSettings;
    }

    public ClusterId getClusterId() {
        return clusterId;
    }

    public ClusterDescription getClusterDescription() {
        return clusterDescription;
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

    BsonDocument asPeriodicDocument() {
        BsonString timestamp = new BsonString(ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT));
        BsonDocument periodicDocument = new BsonDocument();
        periodicDocument.append("timestamp", timestamp);
        periodicDocument.append("type", new BsonInt32(2));
        return periodicDocument;
    }
}
