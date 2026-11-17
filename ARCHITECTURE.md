# Architecture

Three Maven modules: a shared contract and two Spring Boot 4.1 applications on Apache Camel 4.22. In version
0.2.0, about 1,200 lines of main Java (comments included, generated classes not) and 870 lines of tests.

## Modules

| Module | Owns | May depend on |
|---|---|---|
| `interbank-contract` | The RabbitMQ topology (`InterbankTopology`) and the ISO 20022 messages: `Pacs008`, `Pacs002`, `TransactionStatus`, the official XSDs and the classes generated from them, safe XML reading | Spring AMQP's topology types and JAXB only: no Spring context, no connection |
| `integration-service` | Our bank's side: `InterbankGateway` (the one way to send a payment), the Camel routes, the payment status | `interbank-contract` |
| `partner-bank-sim` | The other bank: `StatusReports` decides on each payment, its route answers | `interbank-contract` |

The two applications never depend on each other. Everything they share is in `interbank-contract`, the way
bank-core's OpenAPI file is the contract between bank-core and its callers.

## The round trip

```
POST /api/v1/demo/payments -> DemoPayments (fixed payment, fresh ids) -> InterbankGateway (status SENT)
  -> route send-pacs008 -> exchange "interbank", routing key partner.pacs008 -> quorum queue partner.pacs008
  -> partner-bank-sim route answer-pacs008 -> StatusReports: pacs.002 ACSC
  -> exchange "interbank", routing key bank.pacs002 -> quorum queue bank.pacs002
  -> route receive-pacs002 -> PaymentStatusStore (status ACSC) -> GET /api/v1/demo/payments/{messageId}
```

## Decisions

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
- **The answer is matched by the original message id.** pacs.002 repeats the pacs.008's MsgId, end-to-end id,
  transaction id and UETR. The AMQP message id is the ISO 20022 MsgId, so step 4 can drop duplicate deliveries.
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
- **The status lives in memory for now.** `PaymentStatusStore` keeps the latest 1,000 payments. Step 3 moves
  the status into bank-core's transfer (PENDING until the pacs.002 arrives), and this class goes away.
