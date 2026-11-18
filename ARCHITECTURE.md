# Architecture

Three Maven modules: a shared contract and two Spring Boot 4.1 applications on Apache Camel 4.22. In version
0.4.0, about 1,550 lines of main Java (comments included, generated classes not) and 1,450 lines of tests.

## Modules

| Module | Owns | May depend on |
|---|---|---|
| `interbank-contract` | The RabbitMQ topology (`InterbankTopology`) and the ISO 20022 messages: `Pacs008`, `Pacs002`, `TransactionStatus`, the official XSDs and the classes generated from them, safe XML reading | Spring AMQP's topology types and JAXB only: no Spring context, no connection |
| `integration-service` | Our bank's side, by flow: `outgoing` (flow A: the events, `Pacs008Mapper`, the routes), `events` (the JSON of the Kafka events), and `InterbankProperties` (our bank and the directory of banks) | `interbank-contract` |
| `partner-bank-sim` | The other bank: `StatusReports` decides on each payment (refused if the payee's account is in `partner-bank.closed-accounts`, accepted otherwise), its route answers | `interbank-contract` |

The two applications never depend on each other. Everything they share is in `interbank-contract`, the way
bank-core's OpenAPI file is the contract between bank-core and its callers. The contract between bank-core and
integration-service is the two Kafka events below.

## Flow A: a payment to another bank

```
bank-core's outbox -> Kafka topic interbank.transfers, key: payer's IBAN -> TransferRequested
  -> route transfer-to-pacs008 -> Pacs008Mapper -> exchange "interbank", routing key partner.pacs008
  -> quorum queue partner.pacs008 -> partner-bank-sim route answer-pacs008
  -> StatusReports: pacs.002 ACSC, or RJCT with reason AC04 when the payee's account is closed
  -> exchange "interbank", routing key bank.pacs002 -> quorum queue bank.pacs002
  -> route pacs002-to-status -> TransferStatusChanged SETTLED or REJECTED
  -> Kafka topic interbank.transfer-statuses, key: transfer id -> bank-core's status listener
```

## The events

`TransferRequested`, from bank-core, once the money has left the payer's account:

```json
{"transferId": "0f5bd7c2-3a4e-4c1b-9d0e-6f2a8b1c3d4e",
 "fromIban": "UA843999990000000000000000001", "fromName": "Artem Tkachuk",
 "toIban": "UA643888880000000000000000777", "toName": "Olena Kovalenko",
 "amount": 150.00, "currency": "UAH", "description": "Rent for November",
 "requestedAt": "2026-11-17T08:30:00Z"}
```

`TransferStatusChanged`, from integration-service, once the other bank has answered. `status` is `SETTLED`
(ACSC: bank-core completes the transfer), `REJECTED` (RJCT: bank-core gives the money back) or `PENDING` (PDNG):

```json
{"transferId": "0f5bd7c2-3a4e-4c1b-9d0e-6f2a8b1c3d4e", "status": "SETTLED",
 "reportId": "67a65fa611fe4db9bc67b8271c441990", "reportedAt": "2026-11-17T08:30:01Z"}
```

## From a transfer to a pacs.008

`Pacs008Mapper` fills the message like this; its tests hold each rule.

| pacs.008 | From the transfer |
|---|---|
| `GrpHdr/MsgId`, `PmtId/TxId` | the transfer id without hyphens: 32 characters, where ISO 20022 allows 35 and a UUID has 36 |
| `PmtId/UETR` | the transfer id |
| `PmtId/EndToEndId` | `NOTPROVIDED`, ISO 20022's value when the payer gives no reference of their own |
| `IntrBkSttlmAmt` | the amount, in hryvnia with two decimals; SEP settles UAH only |
| `IntrBkSttlmDt` | today in Kyiv: a payment at 01:00 Kyiv time settles on the Kyiv date, not the UTC one |
| `DbtrAgt`, `CdtrAgt` | our BIC, and the BIC that `interbank.participants` gives for the bank code in `toIban` |
| `Dbtr`, `Cdtr`, `RmtInf/Ustrd` | the payer, the payee and the description, as they are |

It refuses a transfer our bank cannot send: a payer at another bank, a payee at our own bank or at a bank
outside the directory, another currency, or a fraction of a kopiyka (rounding would change the amount).

## Decisions

- **RabbitMQ between the banks, Kafka inside ours.** A pacs.008 is a command for one recipient, to be processed
  once and retried safely: a quorum queue. A transfer event is a fact several systems read and may replay:
  a Kafka topic.
- **One topology, declared by Spring.** `InterbankTopology.declarables()` is the only description of the
  exchange, queues and bindings, and both applications register it as a bean that Spring's `RabbitAdmin`
  declares. Camel's own declaring is switched off (`camel.component.spring-rabbitmq.auto-declare: false`):
  a queue declared twice with different arguments makes the broker refuse the second declaration.
- **A queue per recipient and message type.** A queue's name is its routing key (`partner.pacs008`,
  `bank.pacs002`), so the incoming payments of step 5 get their own queues without touching these.
- **Quorum queues.** They are replicated, survive a broker restart, and stop redelivering a message that keeps
  failing (the delivery limit), instead of looping on it forever.
- **Untrusted XML is parsed with DOCTYPE refused.** That rules out XXE (reading local files, calling internal
  URLs) and entity expansion. The schema check uses the bundled XSD only, never one a document points to.
- **Every id comes from the transfer id.** The UETR is the transfer id, and MsgId and TxId are the transfer id
  without hyphens. A redelivered event therefore repeats a payment's MsgId instead of starting a second
  payment, and the pacs.002, which repeats the UETR, names its transfer without a lookup table. The AMQP
  message id is the ISO 20022 MsgId, so step 4 can drop duplicate deliveries by it.
- **Keyed for order.** Kafka keeps the order within a key: transfers are keyed by the payer's IBAN, so one
  account's transfers stay in order, and statuses by transfer id.
- **Each hop sends only what its contract defines.** Both routes clear the headers they received before
  sending on, so nothing internal to our bank reaches the other bank, and nothing of theirs reaches bank-core.
- **The events are checked where they arrive.** `TransferRequested` checks its fields in its constructor, so a
  broken event never becomes an object. `EventJson` sets the JSON format itself rather than share the web
  layer's mapper, and ignores fields it does not know, so bank-core can add one without breaking this service.
- **At least once.** Camel handles each record before it polls again, and Kafka's auto-commit commits on the
  next poll, so a crash means the event comes again, never that it is lost. Step 4 makes a repeat harmless
  (an idempotent consumer) and sends an event that keeps failing to a dead-letter topic; until then such an
  event is logged and skipped. A refused transfer is logged as well; step 4 reports it to bank-core as REJECTED.
- **Topics are created on first use.** Kafka's auto-creation is enough for the demo stack; a bank creates its
  topics ahead, with their partitions and replication.
- **Classes generated from the official XSDs, behind two records.** The `jaxb-maven-plugin` generates one
  package per message (`iso20022.pacs008`, `iso20022.pacs002`) at build time. The applications see only the
  `Pacs008` and `Pacs002` records, which carry the fields this project uses, so a message version change stays
  inside `interbank-contract`.
- **Checked against the schema both ways.** `Iso20022Xml` validates every document it writes and every one it
  reads. A payment the partner's own check would refuse never leaves (an amount with six decimals, say), and a
  message the standard does not allow is refused before any field is read.
- **Stricter than the schema where our scheme is.** The schema leaves many elements optional, the UETR, the
  transaction id and both accounts among them, and lets one message carry several transactions. Our scheme
  requires every element the records carry and allows one transaction per message and one remittance line.
  Each refusal names the element.
- **Errors never quote values.** A refusal gives the element's path and the rule, such as
  `/Document/FIToFICstmrCdtTrf/CdtTrfTxInf/DbtrAcct/Id/IBAN (cvc-pattern-valid, line 26)`, but not the value
  and not the validator's own exception, whose text does quote it: names and IBANs are personal data and end
  up in logs.
- **A refusal's reason stays here for now.** `Pacs002` reads the first reason the other bank gives as an ISO
  code (`StsRsnInf/Rsn/Cd`, such as AC04 for a closed account), and integration-service logs it. The status
  event carries the outcome only, which is all bank-core acts on; a reason given in the bank's own words
  (`Rsn/Prtry`) leaves the code empty rather than making the report invalid.
- **Kafka confirms an event only once it is on disk.** Kafka normally leaves writing to disk to the operating
  system and relies on copies on other brokers; with a single broker on a desktop, a power cut would lose
  events it had already confirmed. compose sets `KAFKA_LOG_FLUSH_INTERVAL_MESSAGES: 1` for that reason; a bank's
  cluster keeps the default and replicates every partition to three brokers instead.
- **Time to stop cleanly.** compose gives integration-service 30 seconds to stop (`stop_grace_period`) rather
  than Docker's 10: it finishes the message in hand and commits its Kafka offsets, so a restart does not send
  the last payments again.
- **Coverage is a gate, as in bank-core.** `verify` fails below 80 % line coverage per module, unit and
  integration tests together. The classes generated from the XSDs are left out: they are getters and setters
  for hundreds of elements this project never uses.
