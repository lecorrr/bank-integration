# bank-integration

Moves bank-core's payments to other banks the way banks do it: ISO 20022 messages over message brokers,
handled by an Apache Camel service. This is Project 2 of three; bank-core is Project 1.

**Status: step 2 of 8, half done.** One fixed payment goes to a simulated partner bank as a pacs.008 over
RabbitMQ, and the partner's pacs.002 comes back. Both messages are built with classes generated from the
official ISO 20022 schemas and checked against those schemas on the way out and on the way in. Kafka,
PostgreSQL and SFTP already run in the stack and come into use in later steps.

## Layout

| Module | What it is |
|---|---|
| `interbank-contract` | What both banks agree on: the RabbitMQ topology (`InterbankTopology`) and the ISO 20022 messages (`Pacs008`, `Pacs002`), with their official schemas |
| `integration-service` | The Camel service, port 8082: sends pacs.008 and reads the pacs.002 answers |
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

Unit tests (`*Test`) run first, then the integration tests (`*IT`), which start RabbitMQ in Docker through
Testcontainers. The RabbitMQ image tag is `rabbitmq.image` in `pom.xml`; `compose.yaml` uses the same one.

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

Send the demo payment. The answer is `202 Accepted` with the pacs.008 message id, because the partner bank
answers later, over RabbitMQ:

```
curl -si -X POST localhost:8082/api/v1/demo/payments
```

Then ask for its status with that id. It shows `SENT` until the pacs.002 arrives, then `ACSC`:

```
curl -s localhost:8082/api/v1/demo/payments/<messageId>
```

Each application logs its version at startup (`integration-service 0.2.0 started`):

```
docker compose logs integration-service partner-bank-sim
```

The RabbitMQ management UI is at <http://localhost:15672> (user `interbank`, password `interbank`), with the
`interbank` exchange and its two quorum queues. `docker compose stop` stops the stack and keeps its data.

## Run the applications outside Docker

Start RabbitMQ only, build once, then run each application in its own terminal:

```
docker compose up -d rabbitmq
```

```
java -jar integration-service/target/integration-service-0.2.0.jar
```

```
java -jar partner-bank-sim/target/partner-bank-sim-0.2.0.jar
```

## What comes next

The rest of step 2: the payment starts as a transfer event on Kafka instead of the demo endpoint, and its
status goes back to Kafka as an event; the build gets a coverage gate.
