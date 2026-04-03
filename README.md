# dtr - Distributed Task Runner

dtr is a compact, simple, distributed task runner built with Java, Gradle and
Spring Boot.  

Work in progress. Not production ready.  

Overview
--------

This repository contains a Gradle multi-project with three modules:

- `server` - backend service implemented with Spring Boot.
- `client` - an application that connects to the server to run and report tasks.
- `commonlib` - shared domain types and utilities used by both `server` and
  `client`.

Features
--------

The user can define a “data-parallel” task to be executed on remote nodes.  

Modules
-------

- [server](server) - backend service and orchestrator. See
  [server/src/main/resources/application.yaml](server/src/main/resources/application.yaml) for configuration.
- [client](client) - client/runtime that executes tasks. See
  [client/src/main/resources/application-client.yaml](client/src/main/resources/application-client.yaml) for configuration.
- [commonlib](commonlib) - shared utilities and models.

Prerequisites
-------------

- Java JDK 21 or later installed and available on `PATH`.
- Gradle

Quickstart
----------

1. Build the entire project:

```bash
./gradlew build
```

2. In separate terminals, run the server and client for development:

```bash
./gradlew :server:bootRun
./gradlew :client:bootRun
```

Development
-----------

- Import the project into your IDE (IntelliJ or VS Code with Java support).
- Shared code lives in the `commonlib` module.

Contributing
------------

- Fork the repository, create a feature branch and open a pull request.
- Keep diffs small and include tests for new behavior when applicable.