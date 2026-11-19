-- Reconciliation with the partner bank's daily statement (camt.053) of our account there.

-- The settlement date and amount of each payment, copied from its pacs.008 when it is stored, so reconciliation
-- can find and compare payments without reading the XML. Amount and currency are kept as sent: a payment we
-- refused may be in another currency or have more than two decimals.
ALTER TABLE outgoing_payment
    ADD COLUMN settlement_date DATE,
    ADD COLUMN amount          NUMERIC,
    ADD COLUMN currency        CHAR(3);

ALTER TABLE incoming_payment
    ADD COLUMN settlement_date DATE,
    ADD COLUMN amount          NUMERIC,
    ADD COLUMN currency        CHAR(3);

-- Payments stored by earlier versions get the same values from their pacs.008.
WITH pacs008 AS (
    SELECT transfer_id,
           CAST(pacs008 AS xml) AS doc,
           ARRAY[ARRAY['p', 'urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08']] AS ns
    FROM outgoing_payment
    WHERE pacs008 IS NOT NULL
)
UPDATE outgoing_payment o
SET settlement_date = (xpath('/p:Document/p:FIToFICstmrCdtTrf/p:CdtTrfTxInf/p:IntrBkSttlmDt/text()',
                             p.doc, p.ns))[1]::text::date,
    amount          = (xpath('/p:Document/p:FIToFICstmrCdtTrf/p:CdtTrfTxInf/p:IntrBkSttlmAmt/text()',
                             p.doc, p.ns))[1]::text::numeric,
    currency        = (xpath('/p:Document/p:FIToFICstmrCdtTrf/p:CdtTrfTxInf/p:IntrBkSttlmAmt/@Ccy',
                             p.doc, p.ns))[1]::text
FROM pacs008 p
WHERE o.transfer_id = p.transfer_id;

WITH pacs008 AS (
    SELECT uetr,
           CAST(pacs008 AS xml) AS doc,
           ARRAY[ARRAY['p', 'urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08']] AS ns
    FROM incoming_payment
)
UPDATE incoming_payment i
SET settlement_date = (xpath('/p:Document/p:FIToFICstmrCdtTrf/p:CdtTrfTxInf/p:IntrBkSttlmDt/text()',
                             p.doc, p.ns))[1]::text::date,
    amount          = (xpath('/p:Document/p:FIToFICstmrCdtTrf/p:CdtTrfTxInf/p:IntrBkSttlmAmt/text()',
                             p.doc, p.ns))[1]::text::numeric,
    currency        = (xpath('/p:Document/p:FIToFICstmrCdtTrf/p:CdtTrfTxInf/p:IntrBkSttlmAmt/@Ccy',
                             p.doc, p.ns))[1]::text
FROM pacs008 p
WHERE i.uetr = p.uetr;

-- A transfer our bank refused before writing a pacs.008 has none of them.
ALTER TABLE outgoing_payment
    ADD CONSTRAINT outgoing_payment_settlement_with_pacs008
        CHECK ((settlement_date IS NULL) = (pacs008 IS NULL)
            AND (amount IS NULL) = (pacs008 IS NULL)
            AND (currency IS NULL) = (pacs008 IS NULL));

ALTER TABLE incoming_payment
    ALTER COLUMN settlement_date SET NOT NULL,
    ALTER COLUMN amount SET NOT NULL,
    ALTER COLUMN currency SET NOT NULL;

-- The report for a day lists the payments settled that day that no statement has booked.
CREATE INDEX outgoing_payment_settled_on ON outgoing_payment (settlement_date) WHERE state = 'SETTLED';
CREATE INDEX incoming_payment_accepted_on ON incoming_payment (settlement_date) WHERE status = 'ACSC';

-- The partner bank's statements as they came, one per day; a second one for the same day is refused.
CREATE TABLE statement (
    message_id      VARCHAR(35)    PRIMARY KEY,
    statement_id    VARCHAR(35)    NOT NULL,
    account_iban    VARCHAR(34)    NOT NULL,
    statement_date  DATE           NOT NULL,
    currency        CHAR(3)        NOT NULL,
    -- Negative when the account is overdrawn.
    opening_balance NUMERIC(19, 2) NOT NULL,
    closing_balance NUMERIC(19, 2) NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL,
    camt053         TEXT           NOT NULL,
    file_name       VARCHAR(255)   NOT NULL,
    received_at     TIMESTAMPTZ    NOT NULL,
    CONSTRAINT statement_one_per_day UNIQUE (account_iban, statement_date)
);

-- Each entry books one payment on our account: DBIT for our payments to the partner bank, CRDT for its
-- payments to us. line is the entry's place in the statement, from 1.
CREATE TABLE statement_entry (
    message_id    VARCHAR(35)    NOT NULL REFERENCES statement,
    line          INTEGER        NOT NULL,
    -- The partner bank's own reference for the booking (AcctSvcrRef).
    reference     VARCHAR(35)    NOT NULL,
    direction     VARCHAR(4)     NOT NULL CHECK (direction IN ('CRDT', 'DBIT')),
    amount        NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    uetr          UUID           NOT NULL,
    end_to_end_id VARCHAR(35)    NOT NULL,
    PRIMARY KEY (message_id, line),
    CONSTRAINT statement_entry_reference_once UNIQUE (message_id, reference)
);

CREATE INDEX statement_entry_uetr ON statement_entry (uetr);
