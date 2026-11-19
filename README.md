# bank-integration

Moves payments between bank-core and other banks: ISO 20022 messages (pacs.008, pacs.002, pacs.028) over
RabbitMQ, events to and from bank-core over Kafka, an Apache Camel service in between, sanctions screening over
SOAP, and a daily camt.053 statement over SFTP to reconcile against. This is Project 2 of three; bank-core is
Project 1.

Status: steps 1 to 7 of 8 are done.

- **Flow A**, bank-core to another bank: a transfer event becomes a pacs.008 to the partner bank, and its pacs.002
  answer becomes a status event for bank-core.
- **Flow B**, another bank to bank-core: the partner bank's pacs.008 becomes an event for bank-core, and
  bank-core's decision goes back as a pacs.002.
- **Flow C**, reconciliation: the partner bank leaves a daily camt.053 statement of our account with it on its
  SFTP server; integration-service checks every entry against our payments and reports the breaks over REST.
- Every payment is stored in PostgreSQL and screened against the sanctions lists before it leaves our bank or
  reaches bank-core. Late answers are chased with pacs.028 status requests.

```
flow A  bank-core -> Kafka -> integration-service -> RabbitMQ -> partner-bank-sim
        bank-core <- Kafka <- integration-service <- RabbitMQ <- partner-bank-sim

flow B  partner-bank-sim -> RabbitMQ -> integration-service -> Kafka -> bank-core
        partner-bank-sim <- RabbitMQ <- integration-service <- Kafka <- bank-core

flow C  partner-bank-sim -> SFTP (camt.053) -> integration-service -> PostgreSQL, GET /api/reconciliations

        integration-service -> sanctions screening (SOAP, circuit breaker), PostgreSQL
```

| Module | What it is |
|---|---|
| `interbank-contract` | What both banks share: the RabbitMQ topology and the ISO 20022 messages, generated from the official schemas |
| `integration-service` | The Camel service, port 8082: flow A in `outgoing`, flow B in `incoming`, flow C in `reconciliation`, the screening client in `screening` |
| `partner-bank-sim` | The other bank, port 8083: answers our messages, sends payments to us and keeps our account, through a small REST API |

The design, the rules and the failure handling are in [ARCHITECTURE.md](ARCHITECTURE.md).

## Build

Needs JDK 21 or newer and Docker. Copy bank-core's Maven wrapper once:

```
cp -r ~/projects/bank-core/mvnw ~/projects/bank-core/.mvn .
```

```
./mvnw verify
```

Unit tests (`*Test`) run first, then the integration tests (`*IT`) with PostgreSQL, RabbitMQ, Kafka and an SFTP
server in Testcontainers and WireMock in place of the screening service. `verify` fails below 80 % line coverage per
module; the report is `target/site/jacoco/index.html`. The message classes are generated from the XSDs into
`target/generated-sources` and are never edited or committed.

## Run

The demo needs bank-core 0.5 or later, running with its demo accounts (see its README). Build the jars and start
the stack:

```
./mvnw -DskipTests package
```

```
docker compose up -d --build
```

Everything listens on localhost: integration-service 8082, partner-bank-sim 8083, the screening stub 8084,
PostgreSQL 5435, RabbitMQ 5672 (management UI on 15672, `interbank` / `interbank`), Kafka 9092 and SFTP 2222.
Each application logs its version when it starts, for example `integration-service 0.8.0 started`.

To run the applications outside Docker, start only the infrastructure, then each jar in its own terminal:

```
docker compose up -d postgres rabbitmq kafka sanctions-screening sftp
```

```
java -jar integration-service/target/integration-service-0.8.0.jar
```

```
java -jar partner-bank-sim/target/partner-bank-sim-0.8.0.jar
```

## Try it

### Flow A: a transfer from bank-core

Send a transfer to another bank from bank-core (see its README). The partner bank decides by the payee's account:

| Payee | Partner bank answers | Transfer in bank-core |
|---|---|---|
| `UA643888880000000000000000777` | ACSC | `COMPLETED` |
| `UA843888880000000000000000999` | RJCT AC04, account closed | `REJECTED`, money returned |
| `UA443888880000000000000000555` | nothing | `PENDING` |

For the silent account, integration-service sends a pacs.028 after 30 seconds and the partner bank answers PDNG.
The requests continue, the wait doubling up to 10 minutes, and from the fifth one each is logged as an error. The
money stays held: no answer is not a refusal.

### Flow B: a payment from the partner bank

The simulator pays from its customer Olena Kovalenko. Send 250 UAH to bank-core's demo account; the response has
the payment's `uetr`:

```
curl -sS -w '\n' -X POST localhost:8083/api/payments -H 'Content-Type: application/json' -d '{"toIban": "UA843999990000000000000000001", "toName": "Artem Demo", "amount": 250.00, "description": "For the concert tickets"}'
```

See the answers it got, with the `uetr` from the response in place of `UETR`:

```
curl -sS -w '\n' localhost:8083/api/payments/UETR
```

Ask about it with a pacs.028:

```
curl -sS -w '\n' -X POST localhost:8083/api/payments/UETR/status-requests
```

| Payment | Decided by | pacs.002 |
|---|---|---|
| to `UA843999990000000000000000001` in UAH | bank-core, credited | ACSC |
| to `UA733999990000000000000000005`, no such account | bank-core | RJCT AC01 |
| to `UA033999990000000000000000004`, a USD account | bank-core | RJCT CURR |
| with `"currency": "USD"` | integration-service | RJCT AM03 |
| with `"amount": 250.005` | integration-service | RJCT AM12 |
| to `UA643888880000000000000000777`, not our bank | integration-service | RJCT AC01 |
| with `"toName": "Sanctioned Test Entity"` | integration-service, sanctions hit | RJCT RR04 |

### Sanctions screening

The stub knows one listed name, `Sanctioned Test Entity`, as payer, payee or in the remittance information. A
transfer from bank-core to that name never leaves (bank-core hears `REJECTED`); a payment from the partner bank
to it gets RJCT RR04 and never reaches bank-core.

Stop the screening service and send any payment: it waits, nothing is sent or rejected, and the log shows the
circuit breaker opening. Start the service again and the payment continues within a minute.

```
docker compose stop sanctions-screening
```

```
docker compose start sanctions-screening
```

### Flow C: the partner bank's statement

The simulator keeps our bank's account with it: our payments it accepts are debited, its payments we accept are
credited. Just after midnight in Kyiv it leaves the statement of the day before on the SFTP server. To get
today's statement now, after sending a few payments both ways:

```
curl -sS -w '\n' -X POST localhost:8083/api/statements
```

integration-service picks it up within 10 seconds. See the reconciliation, with the `date` from the response in
place of `DATE`:

```
curl -sS -w '\n' localhost:8082/api/reconciliations/DATE
```

Each line of the statement gets a result, and so does each of our payments settled that day that no statement
books. To see the breaks, ask for the statement with mistakes in it instead, with the UETRs of three of the
day's payments in place of `UETR1` to `UETR3`:

```
curl -sS -w '\n' -X POST localhost:8083/api/statements -H 'Content-Type: application/json' -d '{"leaveOut": ["UETR1"], "changeAmounts": {"UETR2": 1.00}, "bookTwice": ["UETR3"], "addUnknown": [{"direction": "DBIT", "amount": 10.00}]}'
```

| Result | Meaning | In the demo |
|---|---|---|
| `MATCHED` | booked, and settled on our side for the same amount | any accepted payment |
| `AMOUNT_MISMATCH` | booked for another amount | `changeAmounts` |
| `DUPLICATE` | booked again after an earlier line | `bookTwice` |
| `UNKNOWN` | booked, but we have no such payment | `addUnknown` |
| `MISSING` | settled on our side that day, booked in no statement | `leaveOut` |
| `NOT_SETTLED` | booked, but on our side it still waits for its answer, or was rejected | a race the demo can't easily show |

A statement closes its day, so a second call the same day sends the next day's statement, with the payments
made since. A payment left out of a statement shows up as `MISSING` in the report for the day it settled. The
report also says whether the opening balance continues the previous statement's closing one: after a faulty
statement the next one doesn't, since the simulator's books stay right. The books are in memory: after a
restart the simulator starts again from 1,000,000.00, and a statement for a day integration-service already has
goes to `error/`.

```
docker compose exec sftp ls -R /home/bnkc/statements
```

### Logs and data

```
docker compose logs integration-service partner-bank-sim
```

```
docker compose exec postgres psql -U integration -d integration -c "SELECT transfer_id, state, screening_outcome, times_sent, status_requests FROM outgoing_payment ORDER BY created_at DESC LIMIT 10" -c "SELECT uetr, status, reason_code, screening_outcome, bank_transfer_id FROM incoming_payment ORDER BY received_at DESC LIMIT 10"
```

```
docker compose exec postgres psql -U integration -d integration -c "SELECT statement_date, message_id, opening_balance, closing_balance, file_name FROM statement ORDER BY statement_date DESC LIMIT 10"
```

Messages integration-service could not use are kept as they came: RabbitMQ messages in the queues ending in
`.dlq`, Kafka events in the topics ending in `.DLT`, each with a `dead-letter-reason` header.

## ISO 20022 schemas

`interbank-contract/src/main/resources/xsd` holds the official schemas from iso20022.org (Payments Clearing and
Settlement, and Bank-to-Customer Cash Management, 2019 releases), unchanged. `SchemaFilesTest` pins their SHA-256 and `.gitattributes` keeps their CRLF
line endings.

| File | Message | Source |
|---|---|---|
| `pacs.008.001.08.xsd` | FIToFICustomerCreditTransferV08 | <https://www.iso20022.org/message/14231/download> |
| `pacs.002.001.10.xsd` | FIToFIPaymentStatusReportV10 | <https://www.iso20022.org/message/14061/download> |
| `pacs.028.001.03.xsd` | FIToFIPaymentStatusRequestV03 | <https://www.iso20022.org/catalogue-messages/iso-20022-messages-archive> |
| `camt.053.001.08.xsd` | BankToCustomerStatementV08 | <https://www.iso20022.org/catalogue-messages/iso-20022-messages-archive> |

## Next

Step 8: AsyncAPI documentation of the Kafka events, one trace id across the three flows, and logs ready for ELK.
