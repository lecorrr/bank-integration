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

/** Builds the pacs.008 for a transfer, or refuses a transfer our bank can't send through SEP. */
@Component
class Pacs008Mapper {

    static final String NOT_PROVIDED = "NOTPROVIDED";
    private static final ZoneId BANK_ZONE = ZoneId.of("Europe/Kyiv");

    private final InterbankProperties bank;
    private final Clock clock;

    Pacs008Mapper(InterbankProperties bank, Clock clock) {
        this.bank = bank;
        this.clock = clock;
    }

    Pacs008 toPacs008(TransferRequested transfer) {
        requireFromOurBank(transfer);
        String creditorAgentBic = creditorAgentBicOf(transfer);
        if (!InterbankProperties.SETTLEMENT_CURRENCY.equals(transfer.currency())) {
            throw new TransferRefusedException(transfer.transferId(), "currency " + transfer.currency()
                    + " is not supported, SEP settles " + InterbankProperties.SETTLEMENT_CURRENCY + " only");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String id = messageIdOf(transfer.transferId());
        return new Pacs008(
                id,
                now,
                NOT_PROVIDED,
                id,
                transfer.transferId().toString(),
                inKopiyky(transfer),
                InterbankProperties.SETTLEMENT_CURRENCY,
                LocalDate.ofInstant(now, BANK_ZONE), // settlement date in Kyiv, not UTC
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
            throw new TransferRefusedException(transfer.transferId(), "toIban is an account at our own bank");
        }
        return bank.participantBic(code).orElseThrow(() -> new TransferRefusedException(transfer.transferId(),
                "toIban belongs to bank " + code + ", which is not a participant"));
    }

    private void requireFromOurBank(TransferRequested transfer) {
        String code = bankCodeOf(transfer.fromIban());
        if (!code.equals(bank.bankCode())) {
            throw new TransferRefusedException(transfer.transferId(),
                    "fromIban belongs to bank " + code + ", not ours (" + bank.bankCode() + ")");
        }
    }

    private static BigDecimal inKopiyky(TransferRequested transfer) {
        try {
            return transfer.amount().setScale(2);
        } catch (ArithmeticException e) {
            throw new TransferRefusedException(transfer.transferId(), "amount has more than two decimals");
        }
    }

    private static String bankCodeOf(String ukrainianIban) {
        return InterbankProperties.bankCodeOf(ukrainianIban).orElseThrow();
    }

    // MsgId and TxId come from the transfer id, so a resend repeats them and the other bank sees the same payment.
    static String messageIdOf(UUID transferId) {
        return transferId.toString().replace("-", "");
    }
}
