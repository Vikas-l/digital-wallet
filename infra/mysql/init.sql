-- Runs once, when the MySQL volume is first created.
-- Each service owns its own database (database-per-service).
CREATE DATABASE IF NOT EXISTS wallet_db;
CREATE DATABASE IF NOT EXISTS rewards_db;
GRANT ALL PRIVILEGES ON wallet_db.* TO 'digitalwallet'@'%';
GRANT ALL PRIVILEGES ON rewards_db.* TO 'digitalwallet'@'%';
FLUSH PRIVILEGES;
