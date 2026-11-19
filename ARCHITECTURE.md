# Architecture

Version 0.7.3. Three Maven modules: a shared contract and two Spring Boot 4.1 applications on Apache Camel 4.22.

## Modules

| Module | Contains | Depends on |
|---|---|---|
| `interbank-contract` | `InterbankTopology` (exchange, queues, bindings), the ISO 20022 messages `Pacs008`, `Pacs002` and `Pacs028` as records over JAXB classes generated from the official XSDs, `StatusReason`, `TransactionStatus` | Spring AMQP's topology types, JAXB |
| `integration-service` | `outgoing` (flow A), `incoming` (flow B), `screening` (SOAP client and circuit breaker), `messaging` (`Routes`: endpoints and retry policies), `events` (Kafka JSON), `InterbankProperties` (our bank and the participants) | `interbank-contract` |
| `partner-bank-sim` | The other bank: `StatusReports` answers our pacs.008 and pacs.028, `PaymentSender` and `PaymentsController` send its own payments, `SentPayments` keeps their answers | `interbank-contract` |

The two applications never depend on each other. bank-core and integration-service share only the Kafka events.

RabbitMQ: one direct exchange, `interbank`, and a quorum queue per recipient and message type, bound by its own
name. A `.dlq` queue holds what its bank could not use, for a person to look at.

| Flow | Partner bank reads | We read | Our dead letters |
|---|---|---|---|
| A | `partner.pacs008`, `partner.pacs028` | `bank.pacs002` | `bank.pacs002.dlq` |
| B | `partner.pacs002` | `bank.pacs008`, `bank.pacs028` | `bank.pacs008.dlq`, `bank.pacs028.dlq` |

## Flow A: a payment to another bank

```
TransferRequested on interbank.transfers
  -> TransferRoute: store as NEW (outgoing_payment), screen, store the screening
       CLEAR -> pacs.008 to partner.pacs008, confirmed -> SENT
       HIT   -> TransferStatusChanged REJECTED to bank-core -> REJECTED, RR04, no pacs.008
  -> commit the Kafka offset

pacs.002 on bank.pacs002
  -> AnswerRoute: Answers decides -> TransferStatusChanged on interbank.transfer-statuses -> record the state

every 5 s: StatusRequestRoute
  -> claims payments without a final answer whose check is due -> pacs.028 to partner.pacs028
```

```
NEW (CLEAR) --pacs.008 confirmed--> SENT --PDNG--> PENDING
NEW, SENT or PENDING --ACSC--> SETTLED   (final)
NEW, SENT or PENDING --RJCT--> REJECTED  (final, bank-core returns the money)
NEW (HIT) ---------------------> REJECTED  (final, RR04, never sent)
refused by Pacs008Mapper ------> REJECTED  (final, never sent)
```

`Answers` holds the rules for each pacs.002 and `AnswersTest` covers all of them:

- ACSC settles and RJCT rejects, once. A final state never changes; the opposite answer after it goes to
  `bank.pacs002.dlq`.
- RJCT AG09 (payment not received): send the same pacs.008 again. RJCT AM05 (duplicate): the other bank refused
  a copy, not the payment, so ask for its status.
- PDNG makes the transfer PENDING and the status requests continue.

Status requests (`StatusRequestPolicy`): the first 30 s after the pacs.008, then the wait doubles up to 10 minutes.
From the fifth request each one is logged as an error. They never stop, and no timeout ever rejects a payment.

## Flow B: a payment from another bank

```
pacs.008 on bank.pacs008
  -> IncomingPaymentRoute: store by UETR (incoming_payment)
       IncomingChecks refuses -> store RJCT -> pacs.002 to partner.pacs002
       screen: HIT -> store RJCT RR04 -> pacs.002 to partner.pacs002
       CLEAR -> IncomingTransferReceived on interbank.incoming-transfers

IncomingTransferDecided on interbank.incoming-transfer-decisions
  -> DecisionRoute: store the answer -> pacs.002 ACSC or RJCT to partner.pacs002 -> commit the Kafka offset

pacs.028 on bank.pacs028
  -> IncomingStatusRequestRoute: the stored answer again; PDNG while undecided, AM05 for a copy we refused,
     AG09 for a payment we never received
```

A repeated pacs.008 gets the stored answer again, the same MsgId included. A second message with a new MsgId
for a known UETR is rejected with AM05 and the payment's own answer stands.

| Payment | pacs.002 |
|---|---|
| creditor agent is not our bank, or the debtor's bank is not a participant | RJCT AGNT |
| not in UAH | RJCT AM03 |
| a fraction of a kopiyka | RJCT AM12 |
| creditor IBAN is not at our bank | RJCT AC01 |
| sanctions hit | RJCT RR04 |
| bank-core: `UNKNOWN_ACCOUNT`, `CURRENCY_MISMATCH`, any other reason | RJCT AC01, CURR, MS03 |
| bank-core credits it | ACSC |

The first broken rule in `IncomingChecks` decides. Whether the account exists, and in which currency, only
bank-core knows.

## Sanctions screening

Both flows screen a payment right before sending it on, after our own checks. The contract is
`xsd/sanctions-screening.xsd` and `wsdl/sanctions-screening.wsdl` (SOAP 1.1, document/literal).

| Screening service | integration-service |
|---|---|
| CLEAR | stores it, the payment continues |
| HIT | stores it, then rejects: REJECTED to bank-core (flow A) or RJCT RR04 (flow B) |
| down, slower than 5 s, HTTP error, soap:Server fault, invalid response | `ScreeningUnavailableException`: the route retries and the payment waits |
| soap:Client fault | `ScreeningRefusedException`: the event or message goes to its DLT or DLQ |

One Resilience4j circuit breaker serves both flows. It opens when half of the last 10 calls failed (counted
from the fifth call), stays open for 30 s, then lets 2 trial calls through. Refused requests are not counted.

## Kafka events

| Topic | Event | Key | Producer |
|---|---|---|---|
| `interbank.transfers` | `TransferRequested` | payer's IBAN | bank-core |
| `interbank.transfer-statuses` | `TransferStatusChanged`: `SETTLED`, `REJECTED` or `PENDING` | transfer id | integration-service |
| `interbank.incoming-transfers` | `IncomingTransferReceived` | payee's IBAN | integration-service |
| `interbank.incoming-transfer-decisions` | `IncomingTransferDecided`: `CREDITED` or `REJECTED` with a reason | UETR | bank-core |
| `interbank.transfers.DLT`, `interbank.incoming-transfer-decisions.DLT` | events integration-service could not use, as they came | as they came | integration-service |

```json
{"transferId": "0f5bd7c2-3a4e-4c1b-9d0e-6f2a8b1c3d4e",
 "fromIban": "UA843999990000000000000000001", "fromName": "Artem Tkachuk",
 "toIban": "UA643888880000000000000000777", "toName": "Olena Kovalenko",
 "amount": 150.00, "currency": "UAH", "description": "Rent for November", "requestedAt": "2026-11-17T08:30:00Z"}

{"transferId": "0f5bd7c2-3a4e-4c1b-9d0e-6f2a8b1c3d4e", "status": "SETTLED",
 "reportId": "67a65fa611fe4db9bc67b8271c441990", "reportedAt": "2026-11-17T08:30:01Z"}

{"uetr": "3b9f7a52-1c4d-4e8f-a0b6-7d2e5c9f1a34",
 "fromIban": "UA643888880000000000000000777", "fromName": "Olena Kovalenko",
 "toIban": "UA843999990000000000000000001", "toName": "Artem Demo",
 "amount": 250.00, "currency": "UAH", "description": "For the concert tickets", "receivedAt": "2026-11-18T09:15:00Z"}

{"uetr": "3b9f7a52-1c4d-4e8f-a0b6-7d2e5c9f1a34", "transferId": "9d1c4b7e-2f3a-4e5b-8c6d-0a1b2c3d4e5f",
 "decision": "REJECTED", "reason": "UNKNOWN_ACCOUNT", "decidedAt": "2026-11-18T09:15:01Z"}
```

`reportId` is the MsgId of the pacs.002 behind the status. A transfer our bank refused itself gets a new id of
our own.

## When something fails

| Failure | What happens |
|---|---|
| PostgreSQL, RabbitMQ or Kafka is down | Kafka routes retry 5 times, 1 to 16 s apart, then read the event again; RabbitMQ routes retry until it works, up to a minute apart |
| The service crashes mid-payment | The uncommitted event or unacknowledged message comes again and continues from the stored payment |
| The same event or message comes twice | Found in the table, nothing is done twice |
| An event or message cannot be used | Goes to its `.DLT` topic or `.dlq` queue as it came, with a `dead-letter-reason` header |
| The partner bank does not answer | pacs.028 status requests; the money stays held |
| The screening service is down | The payment waits; the circuit breaker keeps the retries cheap |
| bank-core is down (flow B) | The payment waits on Kafka; status requests get PDNG |

## Decisions

- **RabbitMQ between banks, Kafka inside ours.** A pacs.008 is a command for one recipient: a quorum queue. A
  transfer event is a fact several systems may read and replay: a Kafka topic.
- **At least once, with idempotent receivers.** A Kafka offset is committed and a RabbitMQ message acknowledged
  only after everything it causes is stored and confirmed. Receivers drop repeats by transfer id, UETR or MsgId.
- **Manual offset commits.** Auto-commit could commit an event that is still failing at shutdown. The routes
  commit as their last step; with `breakOnFirstError` a failed event is read again.
- **Only a definite answer moves money.** A payment without an answer may already be settled at the other bank,
  so no timeout rejects it. bank-core hears REJECTED only from an RJCT or from our own refusal.
- **Publish, then record in flow A; record, then send in flow B.** In flow A a recorded final state makes
  repeats a no-op, so recording first could lose the status event. In flow B repeats are answered from the
  record, so the answer is stored before it goes out.
- **Conditional updates.** Every UPDATE names the states it may start from, so routes running side by side never
  undo each other and a final state never changes.
- **Several instances can run.** Status requests claim payments with `FOR UPDATE SKIP LOCKED` and a one-minute
  lease; flow B inserts with `ON CONFLICT DO NOTHING`.
- **Ids.** Flow A: the UETR is the transfer id, and MsgId and TxId are the transfer id without hyphens, so a
  resend repeats them. Flow B: payments are stored by UETR, since a MsgId is unique per sending bank only.
- **Kafka keys keep order** per account: transfers by the payer's IBAN, incoming payments by the payee's IBAN.
- **One topology.** `InterbankTopology` is declared by Spring's `RabbitAdmin`; Camel's auto-declare is off,
  because a queue declared with different arguments is refused by the broker.
- **Generated classes behind records.** JAXB classes from the official XSDs stay inside `interbank-contract`;
  the applications see `Pacs008`, `Pacs002` and `Pacs028`, so a message version change stays in one module.
- **XML is validated both ways** against the bundled XSDs, DOCTYPE is refused (no XXE), and our rules are
  stricter than the schema (one transaction per message, UETR required).
- **Errors never quote values.** A validation error gives the element path and the rule, not the value: names
  and IBANs are personal data and end up in logs.
- **Headers are cleared at every hop**, so nothing internal reaches the other bank or bank-core.
- **Dead letters keep the message as it came**, with the reason in a header. Not RabbitMQ's own dead-lettering,
  which needs queue arguments the existing queues don't have.
- **Every wait has a limit**: 5 s for RabbitMQ confirms and connections and for a database connection, 30 s for
  a query, 2 s connect and 5 s read for the screening service.
- **Circuit breaker around the SOAP call, not in the routes.** One breaker for one service, testable without
  Camel; the routes keep their own retries.
- **A sanctions refusal says no more than RR04.** Neither the customer nor the other bank learns which list
  matched. The screening id is stored with the payment, and logs name the field and list, never the name.
- **Spring WS client only** (`spring-ws-core` and SAAJ). The web services starter would also start a SOAP server.
- **Database constraints back the rules**, for example a HIT can never be sent. CHECKs on nullable columns
  use `IS NOT DISTINCT FROM`, because a CHECK that evaluates to null passes.
- **Released migrations are never edited.** Flyway checksums them and refuses to start on a mismatch.
- **Kafka writes every message to disk** in the compose stack: a single broker has no replica to recover from.
- **Coverage gate**: 80 % line coverage per module, generated classes excluded.
