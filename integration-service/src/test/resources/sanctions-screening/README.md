# Sanctions screening stubs

WireMock stubs for `ScreenPayment` (see `src/main/resources/wsdl/sanctions-screening.wsdl`). The tests load them
from the classpath, and compose serves the same files on localhost:8084.

| Payment | Response |
|---|---|
| payer is `Sanctioned Test Entity` | HIT, DEBTOR on UA-NSDC |
| payee is `Sanctioned Test Entity` | HIT, CREDITOR on UA-NSDC |
| `Sanctioned Test Entity` in the remittance information | HIT, REMITTANCE_INFORMATION on UA-NSDC |
| anything else | CLEAR |

Each response repeats the request's `PaymentReference` and has a new `ScreeningId`.
