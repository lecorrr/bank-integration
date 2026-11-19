-- Every payment is screened against the sanctions lists before it leaves our bank or reaches bank-core
-- (SanctionsScreening). What the screening service said is kept with the payment, and the first answer stands:
-- CLEAR or HIT, the service's own id for the screening, by which compliance finds the case, and when it came.
-- All three are empty until the payment is screened. A payment stored before this version that never went out
-- is screened before it does.

ALTER TABLE outgoing_payment
    ADD COLUMN screening_outcome VARCHAR(5) CHECK (screening_outcome IN ('CLEAR', 'HIT')),
    ADD COLUMN screening_id      VARCHAR(35),
    ADD COLUMN screened_at       TIMESTAMPTZ,
    ADD CONSTRAINT outgoing_payment_screening_complete
        CHECK ((screening_outcome IS NULL) = (screening_id IS NULL)
            AND (screening_outcome IS NULL) = (screened_at IS NULL)),
    -- A payment on a sanctions list never goes out: it waits to be refused, and then is, for a regulatory reason.
    -- IS NOT DISTINCT FROM, not =: a CHECK fails only on false, and an empty reason_code = 'RR04' is null.
    ADD CONSTRAINT outgoing_payment_hit_never_sent
        CHECK (screening_outcome IS DISTINCT FROM 'HIT'
            OR state = 'NEW'
            OR (state = 'REJECTED' AND reason_code IS NOT DISTINCT FROM 'RR04'));

ALTER TABLE incoming_payment
    ADD COLUMN screening_outcome VARCHAR(5) CHECK (screening_outcome IN ('CLEAR', 'HIT')),
    ADD COLUMN screening_id      VARCHAR(35),
    ADD COLUMN screened_at       TIMESTAMPTZ,
    ADD CONSTRAINT incoming_payment_screening_complete
        CHECK ((screening_outcome IS NULL) = (screening_id IS NULL)
            AND (screening_outcome IS NULL) = (screened_at IS NULL)),
    -- A payment on a sanctions list is refused, for a regulatory reason, in the same statement that stores the hit,
    -- and bank-core never sees it.
    ADD CONSTRAINT incoming_payment_hit_refused
        CHECK (screening_outcome IS DISTINCT FROM 'HIT'
            OR (status IS NOT DISTINCT FROM 'RJCT' AND reason_code IS NOT DISTINCT FROM 'RR04'
                AND bank_transfer_id IS NULL));
