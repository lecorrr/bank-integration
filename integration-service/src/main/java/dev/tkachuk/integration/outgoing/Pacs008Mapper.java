package dev.tkachuk.integration.outgoing;

import dev.tkachuk.integration.InterbankProperties;
import dev.tkachuk.interbank.iso20022.Pacs008;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns bank-core's transfer into the pacs.008 that goes to the other bank:
 *
 * <pre>
 * pacs.008                      from
 * GrpHdr/MsgId                  the transfer id, without hyphens
 * GrpHdr/CreDtTm                now
 * PmtId/EndToEndId              NOTPROVIDED: the payer gave no reference of their own
 * PmtId/TxId                    the transfer id, without hyphens
 * PmtId/UETR                    the transfer id
 * IntrBkSttlmAmt                amount, in hryvnia with two decimals
 * IntrBkSttlmDt                 today in Kyiv
 * Dbtr/Nm, DbtrAcct             fromName, fromIban
 * DbtrAgt                       our BIC
 * Cdtr/Nm, CdtrAcct             toName, toIban
 * CdtrAgt                       the BIC of the bank whose code is in toIban
 * RmtInf/Ustrd                  description
 * </pre>
 *
 * Every id comes from the transfer id, so the same transfer always gives a message with the same ids: a
 * redelivered event repeats a payment's MsgId instead of starting a second payment, and step 4 drops it as a
 * duplicate. The pacs.002 names the payment by the same UETR, so its answer finds the transfer without a lookup.
 */
@Component
class Pacs008Mapper {

    /** ISO 20022's word for an end-to-end id the payer did not give; bank-core's API asks for none. */
    static final String NOT_PROVIDED = "NOTPROVIDED";
    /** The NBU's payment system settles hryvnia only. */
    private static final String HRYVNIA = "UAH";
    /** The business day follows Kyiv time: a payment at 01:00 Kyiv time settles on the Kyiv date. */
    private static final ZoneId BANK_ZONE = ZoneId.of("Europe/Kyiv");

    private final InterbankProperties bank;
    private final Clock clock;

    Pacs008Mapper(InterbankProperties bank, Clock clock) {
        this.bank = bank;
        this.clock = clock;
    }

    /** @throws TransferRefusedException when our bank cannot send this transfer through SEP */
    Pacs008 toPacs008(TransferRequested transfer) {
        requireFromOurBank(transfer);
        String creditorAgentBic = creditorAgentBicOf(transfer);
        if (!HRYVNIA.equals(transfer.currency())) {
            throw new TransferRefusedException(transfer.transferId(),
                    "the currency is " + transfer.currency() + ", and SEP settles " + HRYVNIA + " only");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String id = withoutHyphens(transfer.transferId());
        return new Pacs008(
                id,
                now,
                NOT_PROVIDED,
                id,
                transfer.transferId().toString(),
                inKopiyky(transfer),
                HRYVNIA,
                LocalDate.ofInstant(now, BANK_ZONE),
                transfer.fromName(),
                transfer.fromIban(),
                bank.bic(),
                transfer.toName(),
                transfer.toIban(),
                creditorAgentBic,
                transfer.description());
    }

    private String creditorAgentBicOf(TransferRequested transfer) {
        String code = bankCodeOf(transfer.toIban());
        if (code.equals(bank.bankCode())) {
            throw new TransferRefusedException(transfer.transferId(),
                    "toIban is an account at our own bank, and bank-core moves money between those itself");
        }
        return bank.participantBic(code).orElseThrow(() -> new TransferRefusedException(transfer.transferId(),
                "toIban is an account at bank " + code + ", which is not in the directory of participants"));
    }

    private void requireFromOurBank(TransferRequested transfer) {
        String code = bankCodeOf(transfer.fromIban());
        if (!code.equals(bank.bankCode())) {
            throw new TransferRefusedException(transfer.transferId(),
                    "fromIban is an account at bank " + code + ", not at ours (" + bank.bankCode() + ")");
        }
    }

    /** 150 becomes 150.00; 150.005 is refused rather than rounded, because rounding would change the amount. */
    private static BigDecimal inKopiyky(TransferRequested transfer) {
        try {
            return transfer.amount().setScale(2);
        } catch (ArithmeticException e) {
            throw new TransferRefusedException(transfer.transferId(),
                    "the amount has more than two decimals, and hryvnia have 100 kopiyky");
        }
    }

    /** The six digits after "UA" and the check digits name the bank (TransferRequested checked the format). */
    private static String bankCodeOf(String ukrainianIban) {
        return ukrainianIban.substring(4, 10);
    }

    /** 32 hex characters: within ISO 20022's 35-character limit for ids, where the 36 of a UUID are not. */
    private static String withoutHyphens(UUID id) {
        return id.toString().replace("-", "");
    }
}
