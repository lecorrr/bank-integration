package dev.tkachuk.partnerbank;

import dev.tkachuk.interbank.InterbankTopology;
import java.time.Clock;
import org.springframework.amqp.core.Declarables;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class PartnerBankSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(PartnerBankSimApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** The same topology as integration-service declares, from the same class, so the two always agree. */
    @Bean
    Declarables interbankTopology() {
        return InterbankTopology.declarables();
    }
}
