# MongoDB Telemetry

## Summary

Inspired by [Full Time Diagnostic Data Collection (FTDC)](https://www.mongodb.com/docs/manual/administration/analyzing-mongodb-performance/#full-time-diagnostic-data-capture) for MongoDB server,
this project defines a set of event listeners to be used with the 
[MongoDB Java driver](https://github.com/mongodb/mongo-java-driver)
and analyzed with T2 graphing tools.
                    
## Behavior

Outputs one JSON document per line every 1 second for each open MongoClient to a file called `ftdc.out` 
in the current working directory.  Creates the file if it doesn't exist, otherwise appends to existing file.

## Sample document

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