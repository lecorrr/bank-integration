# Architecture

Three Maven modules: a shared contract and two Spring Boot 4.1 applications on Apache Camel 4.22. In version
0.6.1, about 4,000 lines of main Java (comments included, generated classes not) and 3,700 lines of tests.

## Modules

| Module | Owns | May depend on |
|---|---|---|
| `interbank-contract` | The RabbitMQ topology (`InterbankTopology`) and the ISO 20022 messages: `Pacs008`, `Pacs002`, `Pacs028`, `TransactionStatus`, `StatusReason`, the official XSDs and the classes generated from them, safe XML reading | Spring AMQP's topology types and JAXB only: no Spring context, no connection |
| `integration-service` | Our bank's side, by flow: `outgoing` (flow A: the events, `Pacs008Mapper`, three routes, `Answers` and `StatusRequestPolicy`, which decide, and `OutgoingPayments`, the table), `incoming` (flow B: the events, `IncomingChecks`, which decides what our bank refuses itself, three routes, and `IncomingPayments`, the table), `messaging` (`Routes`: how every route reads, sends, retries and puts aside), `events` (the JSON of the Kafka events), and `InterbankProperties` (our bank and the directory of banks) | `interbank-contract` |
| `partner-bank-sim` | The other bank. As the payee's bank, `StatusReports` decides on each payment (refused if the payee's account is in `partner-bank.closed-accounts`, left without an answer if it is in `silent-accounts`, accepted otherwise) and remembers it, so a duplicate or a status request gets the same answer. As the payer's bank, `PaymentsController` and `PaymentSender` send its payments, and `SentPayments` keeps the answers | `interbank-contract` |

The two applications never depend on each other. Everything they share is in `interbank-contract`, the way
bank-core's OpenAPI file is the contract between bank-core and its callers. The contract between bank-core and
integration-service is the four Kafka events below.

The queues, by bank: a name is its routing key on the `interbank` exchange, and a queue ending in `.dlq` holds
what its bank could not act on, for a person to look at.

| Flow | Partner bank reads | Our bank reads | Our bank puts aside |
|---|---|---|---|
| A | `partner.pacs008` (payments), `partner.pacs028` (status requests) | `bank.pacs002` (answers) | `bank.pacs002.dlq` |
| B | `partner.pacs002` (answers) | `bank.pacs008` (payments), `bank.pacs028` (status requests) | `bank.pacs008.dlq`, `bank.pacs028.dlq` |

## Flow A: a payment to another bank

```
bank-core's outbox -> Kafka topic interbank.transfers, key: payer's IBAN -> TransferRequested
  -> route transfer-to-pacs008 (TransferRoute) -> Pacs008Mapper -> table outgoing_payment, NEW
  -> exchange "interbank", routing key partner.pacs008, confirmed by RabbitMQ -> SENT -> Kafka offset committed
  -> quorum queue partner.pacs008 -> partner-bank-sim route answer-pacs008
  -> StatusReports: pacs.002 ACSC, RJCT with reason AC04 when the payee's account is closed, or nothing
  -> exchange "interbank", routing key bank.pacs002 -> quorum queue bank.pacs002
  -> route pacs002-to-status (AnswerRoute) -> Answers decides -> TransferStatusChanged SETTLED, REJECTED or PENDING
  -> Kafka topic interbank.transfer-statuses, key: transfer id -> bank-core's status listener
  -> table outgoing_payment, SETTLED or REJECTED (or PENDING)

while a payment has no final answer:
  route unanswered-to-pacs028 (StatusRequestRoute), every 5 s -> payments due a status request
  -> pacs.028 on partner.pacs028 -> partner-bank-sim route answer-pacs028
  -> pacs.002 on bank.pacs002: the payment's answer again, PDNG while it has none, or RJCT AG09 if never received
```

### A payment's states

`outgoing_payment` holds one row per transfer, from the first pacs.008 to the final answer, or our bank's own
refusal to send it. A repeated event finds the row and is not decided on again, even if the directory of banks
changed meanwhile. Every change is one conditional UPDATE that names the states it may start from, so the three
routes, which run side by side, never undo each other: a "sent" that comes after the answer leaves the answer
standing, and nothing changes a final answer.

```
NEW ──pacs.008 confirmed──▶ SENT ──PDNG──▶ PENDING
                             ▲                │
                             └──AG09: sent again
NEW, SENT or PENDING ──ACSC──▶ SETTLED     (final)
NEW, SENT or PENDING ──RJCT──▶ REJECTED    (final)
refused by our bank ─────────▶ REJECTED    (final, no pacs.008)
```

`Answers` decides what each pacs.002 does, from the payment's state and the answer; `AnswersTest` holds every
rule:

| The payment is | The answer | Action |
|---|---|---|
| NEW, SENT or PENDING | ACSC | SETTLED to bank-core; final |
| NEW, SENT or PENDING | RJCT, any reason but these two | REJECTED to bank-core, which gives the money back; final |
| NEW, SENT or PENDING | RJCT AG09, PaymentNotReceived | the same pacs.008 again; nothing to bank-core |
| NEW, SENT or PENDING | RJCT AM05, Duplication | a status request now: it refused a copy, not the payment |
| NEW or SENT | PDNG | PENDING to bank-core; keep asking |
| PENDING | PDNG | nothing new |
| SETTLED or REJECTED | the same answer, PDNG, or RJCT AM05 | nothing new |
| SETTLED or REJECTED | the opposite answer | conflict: to the dead-letter queue, logged as an error |

`StatusRequestPolicy` decides when to ask about a payment with no final answer (`status-requests` in
application.yml): the first pacs.028 30 seconds after the pacs.008, then the wait doubles, 1, 2, 4, 8 minutes,
up to 10. From the fifth request each one is logged as an error, and the requests never stop.

## Flow B: a payment from another bank

```
partner-bank-sim (POST /api/payments) -> pacs.008 on the exchange, routing key bank.pacs008
  -> route pacs008-to-bank-core (IncomingPaymentRoute) -> table incoming_payment, by UETR
     refused by our bank (IncomingChecks): stored with its answer -> pacs.002 RJCT on partner.pacs002, done
  -> IncomingTransferReceived -> Kafka topic interbank.incoming-transfers, key: payee's IBAN
  -> bank-core: credits the payee from its settlement account, or refuses (no such account, another currency)
  -> its outbox -> Kafka topic interbank.incoming-transfer-decisions, key: UETR -> IncomingTransferDecided
  -> route decision-to-pacs002 (DecisionRoute) -> the answer stored -> pacs.002 ACSC, or RJCT with the ISO code
     for bank-core's reason, on partner.pacs002, confirmed -> Kafka offset committed
  -> partner-bank-sim route record-pacs002 -> SentPayments; RJCT AG09 sends the payment again

the partner bank asks (POST /api/payments/{uetr}/status-requests) -> pacs.028 on bank.pacs028
  -> route pacs028-to-pacs002 (IncomingStatusRequestRoute) -> a new pacs.002 on partner.pacs002: our answer again,
     PDNG while bank-core decides, RJCT AM05 for a copy we refused, RJCT AG09 for a payment we never received
```

### A payment's answer

`incoming_payment` holds one row per payment, by its UETR, from its pacs.008 to our answer. The answer is
stored before it goes out, and every message that comes again is answered from what is stored, so the partner
bank always gets the same pacs.002, MsgId and all, which it can drop as a copy:

| A pacs.008 comes | What integration-service does |
|---|---|
| for a payment we do not have, that our bank refuses | stores the payment with its refusal, then sends the RJCT |
| for a payment we do not have, otherwise | stores the payment, then sends it on to bank-core |
| again, while bank-core decides | sends the stored payment on again; bank-core decides once, by the UETR |
| again, once answered | sends the stored answer again |
| under a new MsgId, for a UETR we have | RJCT AM05 about that message; the payment's own answer stands |
| reusing the MsgId of another payment from the same bank | to `bank.pacs008.dlq`: an answer names the payment by its MsgId |

`IncomingChecks` decides what our bank refuses itself, before bank-core sees the payment; the first rule broken
gives the reason. Whether the payee's account exists, and in which currency, only bank-core knows:

| The payment | The answer |
|---|---|
| names another bank than ours as the payee's (`CdtrAgt`) | RJCT AGNT |
| comes from a bank outside the directory, or from an account that bank does not keep | RJCT AGNT |
| is not in hryvnia | RJCT AM03 |
| has a fraction of a kopiyka | RJCT AM12 |
| is to an IBAN without our bank code | RJCT AC01 |
| reaches bank-core, which refuses: `UNKNOWN_ACCOUNT` | RJCT AC01 |
| reaches bank-core, which refuses: `CURRENCY_MISMATCH` | RJCT CURR |
| reaches bank-core, which refuses for a reason this service does not know yet | RJCT MS03 |
| reaches bank-core, which credits it | ACSC |

## When something fails

| What happens | What integration-service does |
|---|---|
| The same TransferRequested comes twice | Finds the transfer stored and decides nothing again; the event is committed |
| An event is not a valid TransferRequested | Puts it on `interbank.transfers.DLT` as it came, with `dead-letter-reason` and `dead-letter-origin` headers, and goes on with the next |
| Our bank cannot send the transfer (a bank outside the directory, not hryvnia) | REJECTED to bank-core under an id of its own, then kept as rejected; no pacs.008 |
| PostgreSQL, RabbitMQ or Kafka is down | Tries the step again: for a Kafka event, 5 times 1 to 16 s apart, then starts it over from Kafka; for a RabbitMQ message, until it works, up to a minute apart. Nothing is committed or acknowledged meanwhile |
| The service dies mid-payment | The uncommitted event or unacknowledged message comes again, and finds its payment stored: nothing is sent that is not stored, and the other side drops a copy |
| No answer comes (flow A) | A pacs.028 when it is due; PDNG makes the transfer PENDING; the money stays held, however long it takes |
| The partner bank never received the payment (AG09, flow A) | Sends the same pacs.008 again, same MsgId and UETR |
| A pacs.002 cannot be read, answers no payment of ours, or contradicts a final answer | Puts it on `bank.pacs002.dlq` as it came, with a `dead-letter-reason` header, and logs an error |
| A pacs.008 or pacs.028 cannot be read, or cannot be answered (flow B) | Puts it on `bank.pacs008.dlq` or `bank.pacs028.dlq` as it came, with the reason, and logs an error |
| bank-core decides on a payment we never received, or against the answer we gave | Puts the decision on `interbank.incoming-transfer-decisions.DLT` as it came, and goes on with the next |
| bank-core is down (flow B) | The payment waits on Kafka; the partner bank's status requests get PDNG meanwhile |
| The service stops while a message is being retried | A Kafka event stays uncommitted and a RabbitMQ message goes back to its queue (`rejectAndDontRequeue=false`); both come again after the restart |

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
(ACSC: bank-core completes the transfer), `REJECTED` (RJCT: bank-core gives the money back) or `PENDING` (PDNG).
`reportId` is the MsgId of the pacs.002 that said so; a transfer our bank refused to send is `REJECTED` under a
new id of integration-service's own, since no other bank's report decided it:

```json
{"transferId": "0f5bd7c2-3a4e-4c1b-9d0e-6f2a8b1c3d4e", "status": "SETTLED",
 "reportId": "67a65fa611fe4db9bc67b8271c441990", "reportedAt": "2026-11-17T08:30:01Z"}
```

`IncomingTransferReceived`, from integration-service, for a payment our bank did not refuse itself. The amount
always has two decimals, and a copy of the payment carries what the first message said:

```json
{"uetr": "3b9f7a52-1c4d-4e8f-a0b6-7d2e5c9f1a34",
 "fromIban": "UA643888880000000000000000777", "fromName": "Olena Kovalenko",
 "toIban": "UA843999990000000000000000001", "toName": "Artem Demo",
 "amount": 250.00, "currency": "UAH", "description": "For the concert tickets",
 "receivedAt": "2026-11-18T09:15:00Z"}
```

`IncomingTransferDecided`, from bank-core, once per payment and again for every copy. `decision` is `CREDITED`
or `REJECTED`; `reason` is bank-core's own word for a refusal (`UNKNOWN_ACCOUNT`, `CURRENCY_MISMATCH`), null for a
payment credited, and `transferId` the transfer bank-core made of it:

```json
{"uetr": "3b9f7a52-1c4d-4e8f-a0b6-7d2e5c9f1a34", "transferId": "9d1c4b7e-2f3a-4e5b-8c6d-0a1b2c3d4e5f",
 "decision": "REJECTED", "reason": "UNKNOWN_ACCOUNT", "decidedAt": "2026-11-18T09:15:01Z"}
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
TransferRoute refuses one whose pacs.008 the schema would not accept as well, and reports every refusal to
bank-core as REJECTED.

## Decisions

- **RabbitMQ between the banks, Kafka inside ours.** A pacs.008 is a command for one recipient, to be processed
  once and retried safely: a quorum queue. A transfer event is a fact several systems read and may replay:
  a Kafka topic.
- **One topology, declared by Spring.** `InterbankTopology.declarables()` is the only description of the
  exchange, queues and bindings, and both applications register it as a bean that Spring's `RabbitAdmin`
  declares. Camel's own declaring is switched off (`camel.component.spring-rabbitmq.auto-declare: false`):
  a queue declared twice with different arguments makes the broker refuse the second declaration.
- **A queue per recipient and message type.** A queue's name is its routing key, "recipient.message", so flow B
  came as five more queues without touching flow A's. A `.dlq` queue is its bank's own: no application reads it,
  a person does.
- **Quorum queues.** They are replicated, survive a broker restart, and stop redelivering a message that keeps
  failing (the delivery limit), instead of looping on it forever.
- **Untrusted XML is parsed with DOCTYPE refused.** That rules out XXE (reading local files, calling internal
  URLs) and entity expansion. The schema check uses the bundled XSD only, never one a document points to.
- **Every id comes from the transfer id (flow A).** The UETR is the transfer id, and MsgId and TxId are the
  transfer id without hyphens. A redelivered event therefore finds its payment by the transfer id, and a payment
  sent again repeats its MsgId, by which the other bank drops the copy. The AMQP message id is the ISO 20022
  MsgId. A pacs.002 is matched to its payment by the MsgId it answers, and refused if its UETR is not that
  payment's.
- **A payment from another bank is known by its UETR (flow B).** The partner bank gives the UETR, the one id
  every bank on the way keeps, so integration-service stores the payment by it and bank-core credits it once by
  it. The MsgId belongs to one message, and is unique per sending bank only, so a payment is also found by its
  bank's BIC and MsgId: a second message for the same UETR is a duplicate to refuse (AM05), not a second
  payment, and a MsgId the same bank already used for another payment cannot be answered at all.
- **Keyed for order.** Kafka keeps the order within a key: transfers are keyed by the payer's IBAN, so one
  account's transfers stay in order, and statuses by transfer id; payments from other banks by the payee's
  IBAN, and decisions by UETR.
- **Each hop sends only what its contract defines.** Every route clears the headers it received before sending
  on (`Routes.asInterbankMessage`, `asKafkaEvent`), so nothing internal to our bank reaches the other bank, and
  nothing of theirs reaches bank-core.
- **The events are checked where they arrive.** `TransferRequested` and `IncomingTransferDecided` check their
  fields in their constructors, so a broken event never becomes an object. `EventJson` sets the JSON format
  itself rather than share the web layer's mapper, and ignores fields it does not know, so bank-core can add one
  without breaking this service. A refusal reason bank-core adds later is still a refusal: MS03 until this service
  learns its ISO code.
- **At least once, and a repeat is harmless.** Every step happens before its message is let go: a Kafka event is
  committed, and a RabbitMQ message acknowledged, only once everything it causes is stored and confirmed. A crash
  means the message comes again, never that it is lost, and every receiver drops what it already has:
  integration-service by the transfer id or the UETR, the partner bank by the MsgId, bank-core by the transfer's
  status (ALREADY_APPLIED) or the UETR.
- **Only a definite answer moves money.** A payment without an answer may already be settled at the other
  bank, so giving the money back would pay it twice. No timeout ever rejects a payment: integration-service asks
  (pacs.028), sends again when the other bank never got it (AG09), and after five unanswered requests logs an
  error for a person, but bank-core hears REJECTED only from the other bank's RJCT, or from our own bank's
  refusal to send.
- **The route commits the Kafka offsets, not auto-commit.** Kafka's auto-commit commits what a poll returned,
  so a shutdown during retries would commit an event still failing, and that payment would never go out. With
  `autoCommitEnable=false`, `allowManualCommit=true` and the synchronous commit factory, the route commits each
  event as its last step. `breakOnFirstError=true` makes a failure commit only what went through before it and
  read the failed event again; each round of 5 retries ends well within `max.poll.interval.ms`, so the consumer
  keeps its partitions. Camel 4.22's own source was read for this: `KafkaRecordStreamingProcessor`,
  `SyncCommitManager`, `KafkaFetchRecords`. Both Kafka routes take these settings from `Routes.fromKafka`.
- **Publish first, then record, where a repeat is not answered from the record (flow A).** AnswerRoute sends
  the status event before it stores the final state, because a final state, once stored, makes a repeated answer
  change nothing: recorded first, a lost event would leave bank-core's money held with integration-service
  believing it had told it.
- **Record first, then send, where a repeat is answered from the record (flow B).** Our answer to a payment is
  stored before it goes out, and a message that comes again, a copy of the pacs.008 or a decision from bank-core,
  is answered from what is stored. So a crash in between sends the stored answer later, and the partner bank only
  ever gets one answer per payment, as one pacs.002 sent at least once.
- **Several instances can run.** A status request goes out for a payment its instance has claimed: one UPDATE
  moves the payment's next check a minute ahead and returns it, and `FOR UPDATE SKIP LOCKED` makes two
  instances claim different payments without waiting for each other. A request about an earlier send of the
  payment is not counted (`times_sent` must still match). In flow B, two copies of a payment handled at once by
  two instances store one row (`INSERT ... ON CONFLICT DO NOTHING`), and both answer from it.
- **Dead letters keep the message as it came.** A Kafka event goes to its topic's `.DLT`, keyed as before; a
  RabbitMQ message to its queue's `.dlq`. Both carry the reason in a header, never in a log of the content, and
  nothing is ever dropped silently. The queues are not RabbitMQ's dead-lettering: that would need queue
  arguments the existing queues do not have, and the broker would refuse to redeclare them.
- **Every wait has a limit.** RabbitMQ must confirm a send within 5 seconds and a new connection within 5,
  a database connection comes within 5 seconds and a query answers within 30; a step that times out is retried
  like any other failure, instead of holding a consumer forever.
- **One consumer group, two Kafka routes.** Both Kafka routes read in the group `integration-service`, each its
  own topic, so Kafka keeps one set of offsets per topic for the service. A failed round reconnects its consumer,
  which can pause the other route for a moment while the group rebalances; a busier service would give each
  route a group of its own.
- **Topics are created on first use.** Kafka's auto-creation is enough for the demo stack; a bank creates its
  topics ahead, with their partitions and replication.
- **Classes generated from the official XSDs, behind three records.** The `jaxb-maven-plugin` generates one
  package per message (`iso20022.pacs008`, `iso20022.pacs002`, `iso20022.pacs028`) at build time. The
  applications see only the `Pacs008`, `Pacs002` and `Pacs028` records, which carry the fields this project
  uses, so a message version change stays inside `interbank-contract`.
- **Checked against the schema both ways.** `Iso20022Xml` validates every document it writes and every one it
  reads. A payment the partner's own check would refuse never leaves (an amount with six decimals, say), and a
  message the standard does not allow is refused before any field is read.
- **Stricter than the schema where our scheme is.** The schema leaves many elements optional, the UETR, the
  transaction id and both accounts among them, and lets one message carry several transactions. Our scheme
  requires every element the records carry and allows one transaction per message and one remittance line.
  Each refusal names the element.
- **A message we cannot read is not answered.** Its ids cannot be trusted, and an answer names the payment by
  them, so it goes to its `.dlq` for a person instead; the partner bank asks about the payment and hears AG09
  meanwhile, and sends it again. A message we can read but refuse is answered, with the reason as an ISO code.
- **Errors never quote values.** A refusal gives the element's path and the rule, such as
  `/Document/FIToFICstmrCdtTrf/CdtTrfTxInf/DbtrAcct/Id/IBAN (cvc-pattern-valid, line 26)`, but not the value
  and not the validator's own exception, whose text does quote it: names and IBANs are personal data and end
  up in logs.
- **A refusal's reason stays here (flow A).** `Pacs002` reads the first reason the other bank gives as an ISO
  code (`StsRsnInf/Rsn/Cd`, such as AC04 for a closed account); integration-service logs it and keeps it with the
  payment. The status event carries the outcome only, which is all bank-core acts on; a reason given in the
  bank's own words (`Rsn/Prtry`) leaves the code empty rather than making the report invalid.
- **Kafka confirms an event only once it is on disk.** Kafka normally leaves writing to disk to the operating
  system and relies on copies on other brokers; with a single broker on a desktop, a power cut would lose
  events it had already confirmed. compose sets `KAFKA_LOG_FLUSH_INTERVAL_MESSAGES: 1` for that reason; a bank's
  cluster keeps the default and replicates every partition to three brokers instead.
- **Time to stop cleanly.** On shutdown the routes stop retrying at once (`allowRedeliveryWhileStopping(false)`):
  a failing Kafka event stays uncommitted and a failing RabbitMQ message goes back to its queue, so both come
  again after the restart. compose gives the service 30 seconds to finish the messages in hand
  (`stop_grace_period`).
- **Coverage is a gate, as in bank-core.** `verify` fails below 80 % line coverage per module, unit and
  integration tests together. The classes generated from the XSDs are left out: they are getters and setters
  for hundreds of elements this project never uses.
