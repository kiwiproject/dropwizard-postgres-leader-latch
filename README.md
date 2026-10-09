### Dropwizard Postgres Leader Latch

[![Build](https://github.com/kiwiproject/dropwizard-postgres-leader-latch/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/kiwiproject/dropwizard-postgres-leader-latch/actions/workflows/build.yml?query=branch%3Amain)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=kiwiproject_dropwizard-postgres-leader-latch&metric=alert_status)](https://sonarcloud.io/dashboard?id=kiwiproject_dropwizard-postgres-leader-latch)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=kiwiproject_dropwizard-postgres-leader-latch&metric=coverage)](https://sonarcloud.io/dashboard?id=kiwiproject_dropwizard-postgres-leader-latch)
[![CodeQL](https://github.com/kiwiproject/dropwizard-postgres-leader-latch/actions/workflows/codeql.yml/badge.svg)](https://github.com/kiwiproject/dropwizard-postgres-leader-latch/actions/workflows/codeql.yml)
[![javadoc](https://javadoc.io/badge2/org.kiwiproject/dropwizard-postgres-leader-latch/javadoc.svg)](https://javadoc.io/doc/org.kiwiproject/dropwizard-postgres-leader-latch)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Maven Central](https://img.shields.io/maven-central/v/org.kiwiproject/dropwizard-postgres-leader-latch)](https://central.sonatype.com/artifact/org.kiwiproject/dropwizard-postgres-leader-latch/)

This is a small library that integrates the Postgres-backed leader latch from
[postgres-leader-latch](https://github.com/kiwiproject/postgres-leader-latch) into a Dropwizard service.
It is the Postgres counterpart to
[dropwizard-leader-latch](https://github.com/kiwiproject/dropwizard-leader-latch), which uses Apache
Curator and ZooKeeper, and to
[dropwizard-dynamodb-leader-latch](https://github.com/kiwiproject/dropwizard-dynamodb-leader-latch), which uses DynamoDB.

## Usage

Add the dependency (this brings in `postgres-leader-latch`; you also need a Postgres JDBC driver):

```xml
<dependency>
    <groupId>org.kiwiproject</groupId>
    <artifactId>dropwizard-postgres-leader-latch</artifactId>
    <version>0.1.0</version>
</dependency>
```

Then, in your Dropwizard `Application.run` method:

```java
// The latch keeps one dedicated connection to the primary database and closes every connection it obtains
// from the supplier. Do not return connections from your application's connection pool.
Supplier<Connection> connectionSupplier = () -> DriverManager.getConnection(
        "jdbc:postgresql://my-db.example.com:5432/order-service?connectTimeout=10&tcpKeepAlive=true",
        user, password);
var configuration = LeaderLatchConfiguration.defaults();

var serviceDescriptor = ServiceDescriptor.builder()
        .name("order-service")
        .version("1.2.3")
        .hostname(hostname)
        .port(port)
        .build();

var leaderLatch = ManagedLeaderLatchCreator.startLeaderLatch(
        connectionSupplier, configuration, environment, serviceDescriptor, listeners);

if (leaderLatch.hasLeadership()) {
    // leader-only work
}
```

`ManagedLeaderLatchCreator` creates a `ManagedLeaderLatch`, tells Dropwizard to manage (stop) it, starts it, and
registers a health check and two REST resources. Starting never waits to become the leader. If the latch cannot be
started at all, a `ManagedLeaderLatchException` is thrown so the service does not start half-working; any later problem,
such as Postgres being unreachable, is reported as a value instead of an exception (see
[postgres-leader-latch](https://github.com/kiwiproject/postgres-leader-latch) for how it works, the configuration,
and deployment notes for RDS). Add listeners before starting, either as arguments or with `addLeaderLatchListener`.

Use `ManagedLeaderLatchCreator.from(...)` to configure the creator first (`withoutHealthCheck()`,
`withoutResources()`, `addLeaderLatchListener(...)`) and then call `start()`.

### Endpoints

| Endpoint | Response |
|---|---|
| `GET /kiwi/got-leader-latch` | `204 No Content`; present only when the service participates in a leader latch |
| `GET /kiwi/leader-latch/leader` | `{"leader": true}` or `{"leader": false}` |
| `GET /kiwi/leader-latch/latch` | `id`, `leader`, `leadershipKey`, `leaderId` (the participant holding the leadership lock, or null), and `status` (`IsLeader`, `NotLeader`, `NotStarted`, `Closed`, or `Uncertain`) |

The `/latch` endpoint queries Postgres.

### Health check

Registered as `leaderLatch`. It is unhealthy (CRITICAL) when the latch is not started or is closed, when the leader
cannot be read from Postgres, when there is no leader, and when this instance believes it is the leader but Postgres
shows another participant holding the lock. When there is no leader and this instance could not try to acquire
leadership (for example, it is connected to a standby instead of the primary), the message includes the reason. It runs
one query each time it runs. A monitor that checks every instance of a service should also flag more than one instance
reporting that it is the leader.

## Migrating from dropwizard-leader-latch or dropwizard-dynamodb-leader-latch

The class names are the same, in the package `org.kiwiproject.postgres.leader.dropwizard` instead of
`org.kiwiproject.curator.leader`:

| Before | After |
|---|---|
| depends on `dropwizard-leader-latch` (Curator and ZooKeeper) | depends on `dropwizard-postgres-leader-latch` |
| `ManagedLeaderLatchCreator.startLeaderLatch(curatorClient, environment, descriptor, listeners)` | `ManagedLeaderLatchCreator.startLeaderLatch(connectionSupplier, configuration, environment, descriptor, listeners)` |
| Curator's `LeaderLatchListener` | `org.kiwiproject.postgres.leader.LeaderLatchListener` (same two methods, `isLeader()` and `notLeader()`) |
| `hasLeadership()` could throw | never throws; use `checkLeadershipStatus()` to tell "not leader" from "cannot tell" |
| `whenLeader(...)` returned an `Optional` | returns a `WhenLeaderResult`: `RanAsLeader`, `SkippedNotLeader`, or `ActionFailed` |
| `getParticipants()` | removed; use `getLeader()` for the current leader |
| `/kiwi/leader-latch/latch` returned `latchPath`, `participants`, `state` | returns `leadershipKey`, `leaderId`, `status` |

From `dropwizard-dynamodb-leader-latch`, change the dependency and the imports (`org.kiwiproject.dynamodb.leader`
to `org.kiwiproject.postgres.leader`), and pass a `Supplier<Connection>` and a Postgres `LeaderLatchConfiguration`
instead of a `DynamoDbClient` and a table configuration. The endpoints and health check keep the same shape.
