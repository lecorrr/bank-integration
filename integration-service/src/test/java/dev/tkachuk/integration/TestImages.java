package dev.tkachuk.integration;

/** The Docker images the integration tests run. pom.xml passes in their tags, so each tag lives in one place. */
public final class TestImages {

    private TestImages() {
    }

    /** @param property rabbitmq.image, kafka.image or postgres.image */
    public static String fromSystemProperty(String property) {
        String image = System.getProperty(property);
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("System property " + property + " is not set. Run the tests through Maven "
                    + "(./mvnw verify), or pass -D" + property + " with the tag from pom.xml.");
        }
        return image;
    }
}
