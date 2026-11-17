-- One PostgreSQL for the whole stack: a database and an owner for each application.
-- bank-core moves here in step 3; integration-service starts using its database then as well.
CREATE USER bank WITH PASSWORD 'bank';
CREATE DATABASE bank OWNER bank;

CREATE USER integration WITH PASSWORD 'integration';
CREATE DATABASE integration OWNER integration;
