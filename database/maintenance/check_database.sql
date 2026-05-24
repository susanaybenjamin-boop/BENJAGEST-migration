SELECT DATABASE() AS current_database;

SELECT table_name, table_rows
FROM information_schema.tables
WHERE table_schema = DATABASE()
ORDER BY table_name;

SELECT installed_rank, version, description, success, installed_on
FROM flyway_schema_history
ORDER BY installed_rank;
