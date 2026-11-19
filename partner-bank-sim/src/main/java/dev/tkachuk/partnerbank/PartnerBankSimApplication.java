package dev.tkachuk.partnerbank;

import dev.tkachuk.interbank.InterbankTopology;
import java.time.Clock;
import org.springframework.amqp.core.Declarables;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/** Stands in for the other bank: answers our pacs.008 and pacs.028, and sends us payments when asked over REST. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PartnerBankSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(PartnerBankSimApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Declarables interbankTopology() {
        return InterbankTopology.declarables();
    }
}
