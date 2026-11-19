-- Every transfer to another bank that integration-service has decided on, from its first pacs.008 to the final
-- answer, or its refusal to send it (OutgoingPayments). One row per transfer: the transfer id is the payment's
-- UETR, and a repeated TransferRequested event finds its row here instead of being decided on again.
CREATE TABLE outgoing_payment (
    transfer_id     UUID         PRIMARY KEY,
    -- The pacs.008's MsgId; the other bank's answers name the payment by it.
    message_id      VARCHAR(35)  NOT NULL UNIQUE,
    -- The pacs.008 as it first went out, which a resend repeats unchanged; none for a transfer our bank refused.
    pacs008         TEXT,
    state           VARCHAR(8)   NOT NULL CHECK (state IN ('NEW', 'SENT', 'PENDING', 'SETTLED', 'REJECTED')),
    created_at      TIMESTAMPTZ  NOT NULL,
    -- When the pacs.008 last went out, and how many times it has.
    sent_at         TIMESTAMPTZ,
    times_sent      INTEGER      NOT NULL DEFAULT 0,
    -- Status requests (pacs.028) since the pacs.008 last went out, and when the next one is due; none once final.
    status_requests INTEGER      NOT NULL DEFAULT 0,
    next_check_at   TIMESTAMPTZ,
    -- The final answer: the pacs.002 that gave it (or the id of our own refusal), its reason code, and when.
    report_id       VARCHAR(35),
    reason_code     VARCHAR(4),
    answered_at     TIMESTAMPTZ
);

-- What the status requests look for: payments without a final answer whose next check is due.
CREATE INDEX outgoing_payment_next_check ON outgoing_payment (next_check_at) WHERE state IN ('SENT', 'PENDING');
