-- Every payment from the partner bank to a customer of bank-core, from its pacs.008 to our answer
-- (IncomingPayments). One row per payment, by its UETR: a copy of the pacs.008 finds its row here and gets the
-- same answer again, instead of reaching bank-core as a second payment.
CREATE TABLE incoming_payment (
    uetr             UUID         PRIMARY KEY,
    -- The bank that sent the pacs.008 (its debtor agent), and the pacs.008's MsgId, by which our answers name the
    -- payment. A MsgId is unique per sending bank only.
    sender_bic       VARCHAR(11)  NOT NULL,
    message_id       VARCHAR(35)  NOT NULL,
    -- The pacs.008 as it came; every answer about the payment repeats its ids.
    pacs008          TEXT         NOT NULL,
    received_at      TIMESTAMPTZ  NOT NULL,
    -- Our answer, once bank-core has decided, or our bank has refused the payment itself: ACSC, or RJCT with its
    -- reason; the MsgId of the pacs.002 that carries it; and when. None while bank-core decides.
    status           VARCHAR(4)   CHECK (status IN ('ACSC', 'RJCT')),
    reason_code      VARCHAR(4),
    report_id        VARCHAR(35),
    answered_at      TIMESTAMPTZ,
    -- bank-core's transfer for the payment; none for a payment our bank refused before bank-core saw it.
    bank_transfer_id UUID,
    CONSTRAINT incoming_payment_message_once UNIQUE (sender_bic, message_id),
    CONSTRAINT incoming_payment_answer_complete
        CHECK ((status IS NULL) = (report_id IS NULL) AND (status IS NULL) = (answered_at IS NULL)),
    -- A refusal always says why; an acceptance, or no answer yet, has no reason.
    CONSTRAINT incoming_payment_reason_with_refusal
        CHECK (CASE WHEN status = 'RJCT' THEN reason_code IS NOT NULL ELSE reason_code IS NULL END),
    CONSTRAINT incoming_payment_transfer_after_answer
        CHECK (bank_transfer_id IS NULL OR status IS NOT NULL)
);
