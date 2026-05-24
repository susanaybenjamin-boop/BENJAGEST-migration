# Modelo inicial de base de datos

## Criterio

La base de datos se crea en MariaDB y se versiona con Flyway. El primer modulo persistente es clientes, con una estructura normalizada:

- `customers`: datos fiscales y comerciales del cliente.
- `customer_contacts`: personas de contacto vinculadas a un cliente.
- `customer_addresses`: direcciones separadas por tipo.

El objetivo es evitar columnas repetidas como `telefono_1`, `telefono_2`, `direccion_facturacion`, `direccion_obra`, etc. Cada dato repetible tiene su propia tabla.

## Diagrama ER

```mermaid
erDiagram
    CUSTOMERS ||--o{ CUSTOMER_CONTACTS : tiene
    CUSTOMERS ||--o{ CUSTOMER_ADDRESSES : tiene

    CUSTOMERS {
        char id PK
        varchar legal_name
        varchar trade_name
        varchar tax_identifier UK
        varchar customer_type
        text notes
        boolean active
        timestamp created_at
        timestamp updated_at
    }

    CUSTOMER_CONTACTS {
        char id PK
        char customer_id FK
        varchar full_name
        varchar role_name
        varchar email
        varchar phone
        boolean primary_contact
        boolean active
        timestamp created_at
        timestamp updated_at
    }

    CUSTOMER_ADDRESSES {
        char id PK
        char customer_id FK
        varchar address_type
        varchar line1
        varchar line2
        varchar city
        varchar province
        varchar postal_code
        varchar country
        timestamp created_at
        timestamp updated_at
    }
```

## Proximos modulos

Despues de clientes, el modelo deberia crecer en este orden:

1. Usuarios, roles y permisos.
2. Facturas y lineas de factura.
3. Cobros y vencimientos.
4. Gastos y proveedores.
5. Obras/proyectos de construccion.
6. Documentos adjuntos y auditoria.
