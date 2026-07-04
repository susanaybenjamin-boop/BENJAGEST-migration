-- IMP-H1: clientes sin NIF (importacion historica CONTENDO).
--
-- El diario historico de CONTENDO identifica a los clientes solo por su
-- subcuenta 4300xxx y su nombre ("Clientes - NOMBRE"); muchas fichas no
-- tienen NIF. Hasta ahora `customers.tax_identifier` era NOT NULL con
-- unicidad GLOBAL (uk_customers_tax_identifier), lo que:
--   a) impedia crear un cliente sin NIF, y
--   b) era incorrecto en multi-tenant (dos asesorias/empresas distintas no
--      pueden compartir un cliente por el mero hecho de tener el mismo NIF).
--
-- Se hace el NIF opcional y se cambia la unicidad a (company_id, NIF).
-- En MariaDB UNIQUE admite multiples NULL, asi que varios clientes sin NIF
-- conviven dentro de la misma empresa. Como el UNIQUE global anterior ya
-- garantizaba NIFs distintos, la nueva clave (company_id, tax_identifier)
-- nunca puede fallar por duplicados preexistentes.

ALTER TABLE customers
    MODIFY tax_identifier VARCHAR(32) NULL;

ALTER TABLE customers
    DROP INDEX uk_customers_tax_identifier;

ALTER TABLE customers
    ADD CONSTRAINT uk_customers_company_tax UNIQUE (company_id, tax_identifier);
