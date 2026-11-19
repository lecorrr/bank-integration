package dev.tkachuk.partnerbank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Logs the version at startup. */
@Component
class StartupLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    private final ObjectProvider<BuildProperties> buildProperties;

    StartupLogger(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void logVersion() {
        BuildProperties build = buildProperties.getIfAvailable();
        if (build == null) {
            log.warn("partner-bank-sim started, version unknown: build-info.properties is missing (run mvn package)");
        } else {
            log.info("partner-bank-sim {} started", build.getVersion());
        }
    }
}
