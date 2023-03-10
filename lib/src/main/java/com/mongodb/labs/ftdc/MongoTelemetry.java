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

public final class MongoTelemetry {

    private static final MongoTelemetryTracker TRACKER_INSTANCE = new MongoTelemetryTracker();
    private MongoTelemetry() { }

    static {
        TRACKER_INSTANCE.schedule();
        Runtime.getRuntime().addShutdownHook(new ShutdownHook());
    }

    static class ShutdownHook extends Thread {
        @Override
        public void run() {
            TRACKER_INSTANCE.close();
        }
    }

    public static void addTelemetryListeners(final MongoClientSettings.Builder clientSettingsBuilder) {
        MongoTelemetryListener.addToClientSettings(TRACKER_INSTANCE, clientSettingsBuilder);
    }
}
