# bank-integration

Moves bank-core's payments to other banks the way banks do it: ISO 20022 messages over message brokers,
handled by an Apache Camel service. This is Project 2 of three; bank-core is Project 1.

**Status: step 2 of 8 done: flow A, the happy path.** A transfer event on Kafka becomes a pacs.008 to a
simulated partner bank over RabbitMQ, and the partner's pacs.002 comes back to Kafka as a status event. Both
ISO 20022 messages are built with classes generated from the official schemas and checked against those
schemas on the way out and on the way in. Until bank-core publishes its transfers (step 3), a demo endpoint
publishes them in its place. PostgreSQL and SFTP already run in the stack and come into use in later steps.

```
bank-core ──TransferRequested──▶ Kafka ──▶ integration-service ──pacs.008──▶ RabbitMQ ──▶ partner-bank-sim
bank-core ◀──TransferStatusChanged── Kafka ◀── integration-service ◀──pacs.002── RabbitMQ ◀──┘
```

## Layout

| Module | What it is |
|---|---|
| `interbank-contract` | What both banks agree on: the RabbitMQ topology (`InterbankTopology`) and the ISO 20022 messages (`Pacs008`, `Pacs002`), with their official schemas |
| `integration-service` | The Camel service, port 8082: turns transfer events into pacs.008 messages, and pacs.002 answers into status events |
| `partner-bank-sim` | Plays the other bank, port 8083: answers every pacs.008 with a pacs.002 |

`compose.yaml` runs the whole stack on localhost: PostgreSQL (5435), RabbitMQ (5672, management UI on 15672),
Kafka (9092), SFTP (2222) and both applications. `ARCHITECTURE.md` has the design and the rules.

## Set up (once)

It needs what bank-core needs: JDK 21 and Docker. From `~/projects/bank-integration`, copy bank-core's Maven
wrapper:

```
cp -r ~/projects/bank-core/mvnw ~/projects/bank-core/.mvn .
```

## Build and test

```
./mvnw verify
```

Unit tests (`*Test`) run first, then the integration tests (`*IT`), which start RabbitMQ and Kafka in Docker
through Testcontainers. The image tags are `rabbitmq.image` and `kafka.image` in `pom.xml`; `compose.yaml` uses
the same ones.

`verify` also checks coverage: every module must reach 80 % line coverage from its unit and integration tests
together, classes generated from the schemas left out. Each module's report is `target/site/jacoco/index.html`.

The build generates the message classes from the schemas first (`target/generated-sources/pacs008` and
`pacs002` in `interbank-contract`); they are never edited or committed.

## ISO 20022 schemas

`interbank-contract/src/main/resources/xsd` holds the two official schemas, unchanged, as published by
iso20022.org in the message set *Payments Clearing and Settlement*:

| File | Message | Download |
|---|---|---|
| `pacs.008.001.08.xsd` | FIToFICustomerCreditTransferV08 | <https://www.iso20022.org/message/14231/download> |
| `pacs.002.001.10.xsd` | FIToFIPaymentStatusReportV10 | <https://www.iso20022.org/message/14061/download> |

`SchemaFilesTest` pins their SHA-256, and `.gitattributes` keeps git from converting their CRLF line endings.
To check a fresh download against them:

```
sha256sum interbank-contract/src/main/resources/xsd/*.xsd
```

## Run the demo

Build the jars, then start the stack:

```
./mvnw -DskipTests package
```

```
docker compose up -d --build
```

Ask for the demo transfer, 150.00 UAH to a customer of the partner bank. The service publishes it on Kafka as
bank-core will, and answers `202 Accepted` with the transfer id, because the status comes later:

```
curl -si -X POST localhost:8082/api/v1/demo/payments
```

Then ask for its status with that id. It shows `REQUESTED` until the status event arrives, then `SETTLED`:

```
curl -s localhost:8082/api/v1/demo/payments/<transferId>
```

The logs show the pacs.008 going out and the status coming back; each application also logs its version at
startup (`integration-service 0.3.0 started`):

```
docker compose logs integration-service partner-bank-sim
```

The RabbitMQ management UI is at <http://localhost:15672> (user `interbank`, password `interbank`), with the
`interbank` exchange and its two quorum queues. `docker compose stop` stops the stack and keeps its data.

## Run the applications outside Docker

Start RabbitMQ and Kafka only, build once, then run each application in its own terminal:

```
docker compose up -d rabbitmq kafka
```

```
java -jar integration-service/target/integration-service-0.3.0.jar
```

```
java -jar partner-bank-sim/target/partner-bank-sim-0.3.0.jar
```

## The events on Kafka

Both are JSON, written in `ARCHITECTURE.md` in full. bank-core publishes the first and reads the second from
step 3.

| Topic | Event | Key | Who writes it |
|---|---|---|---|
| `interbank.transfers` | `TransferRequested`: a transfer to a customer of another bank | the payer's IBAN | bank-core (the demo endpoint until step 3) |
| `interbank.transfer-statuses` | `TransferStatusChanged`: `SETTLED`, `REJECTED` or `PENDING` | the transfer id | integration-service |

## What comes next

Step 3 is bank-core 0.2: it publishes its transfers to other banks through an outbox, holds the money as
PENDING, and completes or reverses each transfer when its status event arrives; the demo endpoint goes away.
Step 4 handles the failures: duplicates, retries, dead letters, and a pacs.028 when no answer comes.
