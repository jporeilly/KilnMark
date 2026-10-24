-- Reference lab: a small CRM and billing estate for Kilnmark to discover.
-- All data is synthetic. Some things are left for discovery to find on purpose:
--   * billing.transactions.customer_id has NO foreign key (undeclared join)
--   * billing.invoices.cust_ref holds customer ids as text under another name (renamed key)
--   * crm.customers_archive_2023 duplicates part of crm.customers (redundant copy)

CREATE SCHEMA IF NOT EXISTS crm;
CREATE SCHEMA IF NOT EXISTS billing;
CREATE SCHEMA IF NOT EXISTS lab;

CREATE TABLE crm.customers (
    customer_id      bigint PRIMARY KEY,
    first_name       text NOT NULL,
    last_name        text NOT NULL,
    email            text,
    phone            text,
    date_of_birth    date,
    postcode         text,
    ni_number        text,
    segment          text NOT NULL,
    marketing_opt_in boolean,
    created_at       timestamptz NOT NULL
);
COMMENT ON TABLE crm.customers IS 'One row per retail customer.';

CREATE TABLE crm.accounts (
    account_id   bigint PRIMARY KEY,
    customer_id  bigint NOT NULL REFERENCES crm.customers (customer_id),
    account_type text NOT NULL,
    opened_on    date NOT NULL,
    status       text NOT NULL
);

CREATE TABLE crm.customers_archive_2023 (LIKE crm.customers);

CREATE TABLE billing.transactions (
    txn_id      bigint PRIMARY KEY,
    account_id  bigint NOT NULL,
    customer_id bigint NOT NULL,          -- no foreign key, on purpose
    amount      numeric(12, 2) NOT NULL,
    currency    char(3) NOT NULL,
    txn_at      timestamptz NOT NULL,
    merchant    text NOT NULL,
    card_last4  char(4)
);

CREATE TABLE billing.invoices (
    invoice_id  bigint PRIMARY KEY,
    cust_ref    text NOT NULL,            -- customer id as text, renamed, on purpose
    issued_on   date NOT NULL,
    total       numeric(12, 2) NOT NULL,
    paid        boolean NOT NULL
);
