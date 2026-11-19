# bank-integration

Moves payments between bank-core and other banks the way banks do it: ISO 20022 messages over message brokers,
handled by an Apache Camel service. This is Project 2 of three; bank-core is Project 1.

**Status: step 5 of 8 done: payments in both directions.** Flow A takes bank-core's transfers to other banks:
the transfer event on Kafka becomes a pacs.008 to a simulated partner bank over RabbitMQ, and the partner's
pacs.002 comes back to Kafka as a status event, on which bank-core completes the transfer or gives the money
back. Flow B brings the partner bank's payments in: its pacs.008 becomes an event on which bank-core credits the
payee or refuses, and bank-core's decision goes back to the partner bank as a pacs.002. integration-service keeps
every payment in PostgreSQL: a repeated event or message is never acted on twice, a failed step is retried, a
message it cannot use is put aside in a dead-letter topic or queue, and either bank can ask about a payment with
a pacs.028 status request. Only a definite answer moves money: silence never does. Every ISO 20022 message is
built with classes generated from the official schemas and checked against them on the way out and on the way
in. SFTP already runs in the stack and comes into use in step 7.

```
flow A
bank-core ──TransferRequested──▶ Kafka ──▶ integration-service ──pacs.008──▶ RabbitMQ ──▶ partner-bank-sim
                                           (PostgreSQL)        ──pacs.028──▶ (when the answer is late)
bank-core ◀──TransferStatusChanged── Kafka ◀── integration-service ◀──pacs.002── RabbitMQ ◀──┘

flow B
partner-bank-sim ──pacs.008──▶ RabbitMQ ──▶ integration-service ──IncomingTransferReceived──▶ Kafka ──▶ bank-core
                 ──pacs.028──▶ (to ask)     (PostgreSQL)
partner-bank-sim ◀──pacs.002── RabbitMQ ◀── integration-service ◀──IncomingTransferDecided── Kafka ◀──┘
```

## Layout

| Module | What it is |
|---|---|
| `interbank-contract` | What both banks agree on: the RabbitMQ topology (`InterbankTopology`), the ISO 20022 messages (`Pacs008`, `Pacs002`, `Pacs028`) with their official schemas, and the status reason codes they use (`StatusReason`) |
| `integration-service` | The Camel service, port 8082. Flow A (`outgoing`): transfer events become pacs.008 messages and pacs.002 answers status events, and late answers are asked about. Flow B (`incoming`): the partner's pacs.008 messages become payment events for bank-core, and bank-core's decisions pacs.002 answers. Every payment is kept in PostgreSQL |
| `partner-bank-sim` | Plays the other bank, port 8083. As the payee's bank it answers a pacs.008 with a pacs.002: accepted unless the payee's account is closed, or not at all for a silent account. As the payer's bank it sends payments to bank-core's customers through a small HTTP API, keeps the answers, and sends a payment again when it was never received |

`compose.yaml` runs the whole stack on localhost: PostgreSQL (5435), RabbitMQ (5672, management UI on 15672),
Kafka (9092), SFTP (2222) and both applications. `ARCHITECTURE.md` has the design, the rules, and what happens
when each part fails.

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

Unit tests (`*Test`) run first, then the integration tests (`*IT`), which start PostgreSQL, RabbitMQ and Kafka
in Docker through Testcontainers. The image tags are `postgres.image`, `rabbitmq.image` and `kafka.image` in
`pom.xml`; `compose.yaml` uses the same ones. In integration-service, `TransferFlowIT` and `IncomingFlowIT` play
the partner bank and bank-core, flow A and flow B, losing a payment, answering late or answering nonsense;
`OutgoingPaymentsIT` and `IncomingPaymentsIT` run the payment tables' SQL.

`verify` also checks coverage: every module must reach 80 % line coverage from its unit and integration tests
together, classes generated from the schemas left out. Each module's report is `target/site/jacoco/index.html`.

The build generates the message classes from the schemas first (`target/generated-sources/pacs008`, `pacs002`
and `pacs028` in `interbank-contract`); they are never edited or committed.

## ISO 20022 schemas

`interbank-contract/src/main/resources/xsd` holds the three official schemas, unchanged, as published by
iso20022.org in the message set *Payments Clearing and Settlement* (all three from its 2019 release):

| File | Message | Download |
|---|---|---|
| `pacs.008.001.08.xsd` | FIToFICustomerCreditTransferV08 | <https://www.iso20022.org/message/14231/download> |
| `pacs.002.001.10.xsd` | FIToFIPaymentStatusReportV10 | <https://www.iso20022.org/message/14061/download> |
| `pacs.028.001.03.xsd` | FIToFIPaymentStatusRequestV03 | the message archive, <https://www.iso20022.org/catalogue-messages/iso-20022-messages-archive> |

`SchemaFilesTest` pins their SHA-256, and `.gitattributes` keeps git from converting their CRLF line endings.
To check a fresh download against them:

```
sha256sum interbank-contract/src/main/resources/xsd/*.xsd
```

## Run the demo

Both flows need bank-core 0.5 or later, run with its demo accounts as its README shows (*Run it*). Build the jars,
then start the stack:

```
./mvnw -DskipTests package
```

```
docker compose up -d --build
```

### Flow A: a transfer from bank-core to the partner bank

Send a transfer to another bank from bank-core, as its README shows (*Try it*). The partner bank decides by the
payee's account:

| Payee at the partner bank | What happens | The transfer in bank-core |
|---|---|---|
| `UA643888880000000000000000777` | accepted (pacs.002 ACSC) | `COMPLETED` |
| `UA843888880000000000000000999` | refused, the account is closed (RJCT AC04) | `REJECTED`, the money back on the payer's account |
| `UA443888880000000000000000555` | no answer at all, as if it were lost | stays `PENDING` |

The silent account shows what happens when no answer comes. After 30 seconds integration-service asks with a
pacs.028; the partner bank answers PDNG (it has the payment, undecided), and bank-core hears `PENDING`. The
requests go on, the wait doubling each time up to 10 minutes, and from the fifth one, about 15 minutes in, each
is logged as an error for a person to take up. The money stays held: no answer is not a refusal.

### Flow B: a payment from the partner bank to bank-core

The simulator pays from its customer Olena Kovalenko (`UA643888880000000000000000777`). Send 250 UAH to
bank-core's demo account; the answer is `202` with the payment's `uetr` and `messageId`:

```
curl -sS -w '\n' -X POST localhost:8083/api/payments -H 'Content-Type: application/json' -d '{"toIban": "UA843999990000000000000000001", "toName": "Artem Demo", "amount": 250.00, "description": "For the concert tickets"}'
```

What bank-core's bank answered, with the `uetr` from that answer in place of `UETR`:

```
curl -sS -w '\n' localhost:8083/api/payments/UETR
```

Ask about the payment with a pacs.028; its answer joins the list above:

```
curl -sS -w '\n' -X POST localhost:8083/api/payments/UETR/status-requests
```

Who decides depends on what is wrong, and either way no money moves on a refusal:

| The payment | Decided by | The answer (pacs.002) |
|---|---|---|
| to `UA843999990000000000000000001`, in UAH | bank-core: credited, the statement shows an `INCOMING` entry | ACSC |
| to `UA733999990000000000000000005`, an account bank-core does not keep | bank-core | RJCT AC01 |
| to `UA033999990000000000000000004`, bank-core's dollar account | bank-core | RJCT CURR |
| with `"currency": "USD"` | integration-service, before bank-core sees it | RJCT AM03 |
| with `"amount": 250.005` | integration-service | RJCT AM12 |
| to `UA643888880000000000000000777`, an account at the partner bank itself | integration-service | RJCT AC01 |

### What to look at

The logs show each message going out and coming in, a refusal with its ISO 20022 reason code; each application
also logs its version at startup (`integration-service 0.6.1 started`):

```
docker compose logs integration-service partner-bank-sim
```

The payments integration-service holds, newest first, flow A and then flow B:

```
docker compose exec postgres psql -U integration -d integration -c "SELECT transfer_id, state, times_sent, status_requests, next_check_at FROM outgoing_payment ORDER BY created_at DESC LIMIT 10" -c "SELECT uetr, message_id, status, reason_code, report_id, bank_transfer_id FROM incoming_payment ORDER BY received_at DESC LIMIT 10"
```

The RabbitMQ management UI is at <http://localhost:15672> (user `interbank`, password `interbank`), with the
`interbank` exchange and its nine quorum queues. A message integration-service could not act on waits in a queue
ending in `.dlq` (`bank.pacs002.dlq`, `bank.pacs008.dlq`, `bank.pacs028.dlq`), with the reason in its
`dead-letter-reason` header (*Get messages* on the queue's page); an event on Kafka it could not act on waits in a
topic ending in `.DLT`. `docker compose stop` stops the stack and keeps its data.

## Run the applications outside Docker

Start PostgreSQL, RabbitMQ and Kafka only, build once, then run each application in its own terminal:

```
docker compose up -d postgres rabbitmq kafka
```

```
java -jar integration-service/target/integration-service-0.6.1.jar
```

```
java -jar partner-bank-sim/target/partner-bank-sim-0.6.1.jar
```

## The events on Kafka

Every event is JSON, written in `ARCHITECTURE.md` in full. bank-core publishes the ones marked so, through its
outbox, and reads the others.

| Topic | Event | Key | Who writes it |
|---|---|---|---|
| `interbank.transfers` | `TransferRequested`: a transfer to a customer of another bank | the payer's IBAN | bank-core |
| `interbank.transfer-statuses` | `TransferStatusChanged`: `SETTLED`, `REJECTED` or `PENDING` | the transfer id | integration-service |
| `interbank.incoming-transfers` | `IncomingTransferReceived`: a payment from another bank to a customer of bank-core | the payee's IBAN | integration-service |
| `interbank.incoming-transfer-decisions` | `IncomingTransferDecided`: `CREDITED`, or `REJECTED` with a reason | the UETR | bank-core |
| `interbank.transfers.DLT` | an event from `interbank.transfers` that is not a valid transfer, as it came | as it came | integration-service |
| `interbank.incoming-transfer-decisions.DLT` | a decision integration-service cannot act on, as it came | as it came | integration-service |

## The partner bank's API

`partner-bank-sim` sends its payments on request, for demos and tests (`PaymentsController`):

| Request | What it does |
|---|---|
| `POST /api/payments` with `toIban`, `toName`, `amount`, `description`, and `currency` (UAH when left out) | sends a pacs.008 under a new UETR and MsgId; `202` with both |
| `GET /api/payments/{uetr}` | the payment, how many times it went out, and every answer about it |
| `POST /api/payments/{uetr}/status-requests` | sends a pacs.028 about it; `202` with the request's MsgId |

## What comes next

Step 6 screens every payment, both ways, against a sanctions list over SOAP, with a circuit breaker in front.
