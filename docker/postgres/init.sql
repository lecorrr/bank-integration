-- One PostgreSQL for the whole stack, run once on an empty volume: a database and an owner for each application.
-- integration-service keeps its outgoing payments in its own, where Flyway creates the table. bank-core's is ready
-- here too, though bank-core still runs on the database of its own compose file.
CREATE USER bank WITH PASSWORD 'bank';
CREATE DATABASE bank OWNER bank;

CREATE USER integration WITH PASSWORD 'integration';
CREATE DATABASE integration OWNER integration;
