# MongoDB Telemetry

## Summary

Inspired by [Full Time Diagnostic Data Collection (FTDC)](https://www.mongodb.com/docs/manual/administration/analyzing-mongodb-performance/#full-time-diagnostic-data-capture) for MongoDB server,
this project defines a set of event listeners to be used with the 
[MongoDB Java driver](https://github.com/mongodb/mongo-java-driver)
and analyzed with T2 graphing tools.
                    
## Behavior

Outputs one JSON document per line every 1 second for each open MongoClient to a file called `metrics.interim` 
in the `diagnostics.data` directory withing the current working directory.  Creates the file if it doesn't exist, 
otherwise appends to existing file.  On exit, moves `metrics.interim` to `metrics.<start timestamp>`

## Sample document

### Type 0 documents

Documents of this type are written once on startup.  It's basically just the client metadata minus appName.

```json
{
  "timestamp": "2023-03-07T15:15:41.340232Z",
  "type": 0,
  "metadata": {
    "driver": {
      "name": "mongo-java-driver",
      "version": "4.0.0"
    },
    "os": {
      "type": "Darwin",
      "name": "Mac OS X",
      "architecture": "aarch64",
      "version": "12.6.3"
    },
    "platform": "Java/Amazon.com Inc./17.0.6+10-LTS"
  }
}
```

### Type 1 documents

Documents of this type are written once per MongoClient creation

```json
{
  "timestamp": "2023-03-07T17:18:51.511Z",
  "type": 1,
  "clientId": "640771faab220d153b45e445",
  "settings": {
    "directConnection": false,
    "hosts": [
      "<host1>", "<host2>"
    ],
    "requiredType": "REPLICA_SET",
    "replicaSet": "rs1",
    "serverSelectionTimeoutMS": 30000,
    "localThresholdMS": 15,
    "retryReads": true,
    "retryWrites": true,
    "compressors": [],
    "uuidRepresentation": "UNSPECIFIED",
    "maxPoolSize": 100,
    "minPoolSize": 0,
    "maxIdleTimeMS": 0,
    "waitQueueTimeoutMS": 120000,
    "maxConnecting": 2,
    "connectTimeoutMS": 10000,
    "socketTimeoutMS": 0,
    "tls": false
  }
}
```

or with `mongodb+srv` protocol:

```json
{
  "timestamp": "2023-03-07T17:07:27.795Z",
  "type": 1,
  "clientId": "64076f4e5fe95d3f644c51bc",
  "settings": {
    "directConnection": false,
    "srvHost": "<srv host>",
    "requiredType": "REPLICA_SET",
    "replicaSet": "rs1",
    "serverSelectionTimeoutMS": 30000,
    "localThresholdMS": 15,
    "retryReads": true,
    "retryWrites": true,
    "compressors": [],
    "uuidRepresentation": "UNSPECIFIED",
    "maxPoolSize": 100,
    "minPoolSize": 0,
    "maxIdleTimeMS": 0,
    "waitQueueTimeoutMS": 120000,
    "maxConnecting": 2,
    "connectTimeoutMS": 10000,
    "socketTimeoutMS": 0,
    "tls": true
  }
}
```

### Type 2 documents

Documents of this type are written once per second

```json
{
  "timestamp": "2023-03-06T22:01:11.708Z",
  "type": 2,
  "clientId": "640661585171c447d1ac4af6",
  "topology": {
    "type": "STANDALONE",
    "servers": [
      {
        "address": "127.0.0.1:27017",
        "state": "CONNECTED",
        "type": "STANDALONE",
        "rttMillis": 287.99
      }
    ]
  },
  "commands": {
    "inProgress": 0,
    "completed": 2200,
    "socketError": 0,
    "socketTimeout": 0,
    "serverErrorResponses": {
      "2": 1100
    },
    "gte0Millis": 2188,
    "gte10Millis": 9,
    "gte100Millis": 1,
    "gte1000Millis": 2,
    "gte10000Millis": 0,
    "gte100000Millis": 0,
    "gte1000000Millis": 0
  },
  "connectionPools": [
    {
      "address": "127.0.0.1:27017",
      "checkOutsInProgress": 0,
      "operationsInProgress": 0,
      "ready": 1,
      "cleared": 0,
      "opened": 1,
      "closed": 0,
      "checkOutStarted": 2200,
      "checkOutFailed": 0,
      "checkedOut": 2200,
      "checkedIn": 2200
    }
  ]
}
```

## Usage

Add the dependency to your project, e.g.:

```xml
<dependency>
    <groupId>org.mongodb</groupId>
    <artifactId>mongodb-ftdc</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```
    
For each `MongoClient` used in your application, create and configure an instance of 
`MongoClientSettings.Builder`.  Right before building it, call
`com.mongodb.labs.ftdc.MongoTelemetry#addTelemetryListeners`.  Then create the `MongoClient`.   

```java
MongoClientSettings.Builder clientSettingsBuilder = MongoClientSettings.builder();
ClientSettingsBuilder.applyConnectionString(new ConnectionString("mongodb://localhost"));

MongoTelemetry.addTelemetryListeners(clientSettingsBuilder);

MongoClient client = MongoClients.create(clientSettingsBuilder.build());
```