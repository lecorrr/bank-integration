-- Runs once on an empty volume: a database and an owner for each application.
-- Flyway creates integration's tables; bank-core still uses the database from its own compose file.
CREATE USER bank WITH PASSWORD 'bank';
CREATE DATABASE bank OWNER bank;

CREATE USER integration WITH PASSWORD 'integration';
CREATE DATABASE integration OWNER integration;
