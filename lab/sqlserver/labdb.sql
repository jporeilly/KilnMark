-- Reference lab: labdb, a small field-service and sales estate on SQL Server for Kilnmark to
-- discover. Run once by lab-sqlserver-init (seed.sh) with sqlcmd -I -b; the rows are the same
-- every time. All data is synthetic: names come from the short invented lists below, emails use
-- example.com and phone numbers use the fictional 555-0100 to 555-0199 range.
--
-- Left in on purpose, for profiling and quality rules to find:
--   * null emails: 10 in hr.employees, 23 in sales.customers (11 of them copied into staging)
--   * ops.customer_import_staging has no primary key, and customer codes C0000040, C0000120 and
--     C0000360 appear twice in it
--   * out-of-range dates: hire_date 2099-12-31 (employees 37, 142, 219), date_of_birth
--     1900-01-01 (employees 88, 176), ordered_at in 1900 (orders 404, 1212) and 2207 (order 777),
--     closed_at before opened_at (work orders 100013, 100111, 100500, 100875)
--   * phone numbers written five different ways
-- Synthetic personal data lives in hr.employees, sales.customers, ops.work_orders (site contacts)
-- and ops.customer_import_staging; their descriptions say so and the columns carry SQL Server
-- sensitivity classifications.

SET NOCOUNT ON;
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET NUMERIC_ROUNDABORT OFF;
GO

CREATE DATABASE labdb;
GO
ALTER DATABASE labdb SET RECOVERY SIMPLE;
ALTER DATABASE labdb SET ALLOW_SNAPSHOT_ISOLATION ON;
GO

-- Lab-only logins. kiln_reader is what Kilnmark scans with; kiln_writer is for pipeline writes.
IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'kiln_reader')
    CREATE LOGIN kiln_reader WITH PASSWORD = N'Lab-Reader-2026', DEFAULT_DATABASE = labdb,
        CHECK_POLICY = ON, CHECK_EXPIRATION = OFF;
ELSE
    ALTER LOGIN kiln_reader WITH PASSWORD = N'Lab-Reader-2026', DEFAULT_DATABASE = labdb;
IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'kiln_writer')
    CREATE LOGIN kiln_writer WITH PASSWORD = N'Lab-Writer-2026', DEFAULT_DATABASE = labdb,
        CHECK_POLICY = ON, CHECK_EXPIRATION = OFF;
ELSE
    ALTER LOGIN kiln_writer WITH PASSWORD = N'Lab-Writer-2026', DEFAULT_DATABASE = labdb;
GO

USE labdb;
GO

CREATE SCHEMA sales AUTHORIZATION dbo;
GO
CREATE SCHEMA hr AUTHORIZATION dbo;
GO
CREATE SCHEMA ops AUTHORIZATION dbo;
GO
CREATE SCHEMA warehouse AUTHORIZATION dbo;
GO

-- kiln_reader reads everything and changes nothing. It may also read view definitions and the
-- columns' sensitivity classifications.
CREATE USER kiln_reader FOR LOGIN kiln_reader;
ALTER ROLE db_datareader ADD MEMBER kiln_reader;
GRANT VIEW DEFINITION TO kiln_reader;
GRANT VIEW ANY SENSITIVITY CLASSIFICATION TO kiln_reader;

-- kiln_writer creates and fills tables in warehouse only: CREATE TABLE needs ALTER on the
-- schema too, and it has that on warehouse alone.
CREATE USER kiln_writer FOR LOGIN kiln_writer WITH DEFAULT_SCHEMA = warehouse;
GRANT CREATE TABLE TO kiln_writer;
GRANT ALTER, SELECT, INSERT, UPDATE, DELETE, REFERENCES ON SCHEMA::warehouse TO kiln_writer;
GO

-- Helpers for generating rows (temporary tables last for this whole sqlcmd session).
CREATE TABLE #n (n int NOT NULL PRIMARY KEY);
WITH d AS (SELECT v FROM (VALUES (0), (1), (2), (3), (4), (5), (6), (7), (8), (9)) AS t (v))
INSERT INTO #n (n)
SELECT 1 + a.v + 10 * b.v + 100 * c.v + 1000 * e.v
FROM d AS a CROSS JOIN d AS b CROSS JOIN d AS c CROSS JOIN d AS e;   -- 1 to 10,000

-- Invented first names (name as shown, slug for email addresses); two use non-ASCII letters.
CREATE TABLE #first (i int NOT NULL PRIMARY KEY, name nvarchar(20) NOT NULL, slug varchar(20) NOT NULL);
INSERT INTO #first (i, name, slug) VALUES
    (0, N'Ada', 'ada'), (1, N'Bram', 'bram'), (2, N'Cleo', 'cleo'), (3, N'Dev', 'dev'),
    (4, N'Esme', 'esme'), (5, N'Finn', 'finn'), (6, N'Gita', 'gita'), (7, N'Hugo', 'hugo'),
    (8, N'Ines', 'ines'), (9, N'Jonah', 'jonah'), (10, N'Kira', 'kira'), (11, N'Luca', 'luca'),
    (12, N'Mira', 'mira'), (13, N'Niko', 'niko'), (14, N'Zo' + NCHAR(235), 'zoe'),
    (15, N'S' + NCHAR(248) + N'ren', 'soren');

-- Invented surnames.
CREATE TABLE #last (i int NOT NULL PRIMARY KEY, name nvarchar(20) NOT NULL, slug varchar(20) NOT NULL);
INSERT INTO #last (i, name, slug) VALUES
    (0, N'Quenby', 'quenby'), (1, N'Tarrowby', 'tarrowby'), (2, N'Mistlecombe', 'mistlecombe'),
    (3, N'Pendlewick', 'pendlewick'), (4, N'Orrinson', 'orrinson'), (5, N'Hazelgrave', 'hazelgrave'),
    (6, N'Fennimoor', 'fennimoor'), (7, N'Brackwater', 'brackwater'), (8, N'Thistlecott', 'thistlecott'),
    (9, N'Wolvenden', 'wolvenden'), (10, N'Larkmoor', 'larkmoor'), (11, N'Dunmarrow', 'dunmarrow'),
    (12, N'Ellingtree', 'ellingtree'), (13, N'Corriwen', 'corriwen'), (14, N'Ashlowe', 'ashlowe'),
    (15, N'Vantreece', 'vantreece');

-- Invented company words and trades.
CREATE TABLE #word (i int NOT NULL PRIMARY KEY, name nvarchar(20) NOT NULL);
INSERT INTO #word (i, name) VALUES
    (0, N'Zorvex'), (1, N'Quillmar'), (2, N'Brantovo'), (3, N'Felsquay'), (4, N'Matrovin'),
    (5, N'Okkalune'), (6, N'Tessivar'), (7, N'Drumvane'), (8, N'Ystrelle'), (9, N'Gorran'),
    (10, N'Haverquist'), (11, N'Pellowen');
CREATE TABLE #trade (i int NOT NULL PRIMARY KEY, name nvarchar(20) NOT NULL);
INSERT INTO #trade (i, name) VALUES
    (0, N'Bakeries'), (1, N'Cold Stores'), (2, N'Dairies'), (3, N'Laundries'),
    (4, N'Breweries'), (5, N'Print Works'), (6, N'Car Washes'), (7, N'Leisure Centres');

-- Area codes for the fictional 555-01xx numbers.
CREATE TABLE #area (i int NOT NULL PRIMARY KEY, code char(3) NOT NULL);
INSERT INTO #area (i, code) VALUES (0, '201'), (1, '312'), (2, '415'), (3, '503'), (4, '617'), (5, '720');

-- Product kinds.
CREATE TABLE #kind (i int NOT NULL PRIMARY KEY, name nvarchar(30) NOT NULL, weight_kg decimal(6, 1) NOT NULL);
INSERT INTO #kind (i, name, weight_kg) VALUES
    (0, N'Circulation pump', 12.5), (1, N'Valve kit', 2.0), (2, N'Temperature sensor', 0.3),
    (3, N'Controller', 1.8), (4, N'Filter pack', 4.2), (5, N'Gasket set', 0.6),
    (6, N'Drive motor', 18.0), (7, N'Relay board', 0.9);
GO

-- hr ---------------------------------------------------------------------------------------------

CREATE TABLE hr.departments (
    department_id   int IDENTITY(1, 1) NOT NULL CONSTRAINT pk_departments PRIMARY KEY,
    department_code char(3)      NOT NULL CONSTRAINT uq_departments_code UNIQUE,
    department_name nvarchar(60) NOT NULL
);

INSERT INTO hr.departments (department_code, department_name) VALUES
    ('EXE', N'Executive office'), ('SAL', N'Sales'), ('SVC', N'Field service'), ('DSP', N'Dispatch'),
    ('WHS', N'Warehouse'), ('FIN', N'Finance'), ('PPL', N'People'), ('ITS', N'IT support');

CREATE TABLE hr.employees (
    employee_id   int IDENTITY(1, 1) NOT NULL CONSTRAINT pk_employees PRIMARY KEY,
    employee_uid  uniqueidentifier NOT NULL CONSTRAINT uq_employees_uid UNIQUE,
    first_name    nvarchar(40)  NOT NULL,
    last_name     nvarchar(40)  NOT NULL,
    full_name     AS (first_name + N' ' + last_name),
    email         nvarchar(120) NULL,
    phone         varchar(30)   NULL,
    date_of_birth date          NULL,
    hire_date     date          NOT NULL,
    department_id int           NOT NULL
        CONSTRAINT fk_employees_department REFERENCES hr.departments (department_id),
    manager_id    int           NULL
        CONSTRAINT fk_employees_manager REFERENCES hr.employees (employee_id),
    salary        money         NOT NULL,
    is_active     bit           NOT NULL CONSTRAINT df_employees_is_active DEFAULT (1)
);

CREATE UNIQUE INDEX ux_employees_email ON hr.employees (email) WHERE email IS NOT NULL;
GO

-- 250 employees. Employee k (1 to 8) heads department k; everyone else is in department
-- 1 + (id - 1) % 8 and reports to its head, so sales (2) are ids 2, 10, 18 ... and field service
-- (3) are ids 3, 11, 19 ...
INSERT INTO hr.employees (employee_uid, first_name, last_name, email, phone, date_of_birth, hire_date,
                          department_id, salary, is_active)
SELECT
    CAST(HASHBYTES('MD5', CONCAT('employee-', n.n)) AS uniqueidentifier),
    f.name,
    l.name,
    CASE WHEN n.n % 23 = 0 THEN NULL ELSE CONCAT(f.slug, '.', l.slug, '.', n.n, '@example.com') END,
    CASE n.n % 5
        WHEN 0 THEN CONCAT('(', a.code, ') 555-01', p.s)
        WHEN 1 THEN CONCAT(a.code, '-555-01', p.s)
        WHEN 2 THEN CONCAT('+1 ', a.code, ' 555 01', p.s)
        WHEN 3 THEN CONCAT(a.code, '.555.01', p.s)
        ELSE CONCAT(a.code, '55501', p.s)
    END,
    DATEADD(day, (n.n * 211) % 12775, CAST('1960-01-01' AS date)),
    DATEADD(day, (n.n * 97) % 4000, CAST('2014-01-06' AS date)),
    1 + (n.n - 1) % 8,
    CAST(32000 + ((n.n * 79) % 480) * 100 AS money),
    CASE WHEN n.n % 19 = 0 THEN 0 ELSE 1 END
FROM #n AS n
JOIN #first AS f ON f.i = n.n % 16
JOIN #last AS l ON l.i = (n.n / 16) % 16
JOIN #area AS a ON a.i = (n.n / 5) % 6
CROSS APPLY (SELECT RIGHT(CONCAT('0', n.n % 100), 2) AS s) AS p
WHERE n.n <= 250
ORDER BY n.n;

UPDATE hr.employees SET manager_id = 1 + (employee_id - 1) % 8 WHERE employee_id > 8;

-- Planted problems: hire dates in the future, placeholder birth dates.
UPDATE hr.employees SET hire_date = '2099-12-31' WHERE employee_id IN (37, 142, 219);
UPDATE hr.employees SET date_of_birth = '1900-01-01' WHERE employee_id IN (88, 176);
GO

-- sales ------------------------------------------------------------------------------------------

CREATE TABLE sales.customers (
    customer_id        int IDENTITY(1, 1) NOT NULL CONSTRAINT pk_customers PRIMARY KEY,
    customer_code      char(8)           NOT NULL,
    company_name       nvarchar(100)     NOT NULL,
    contact_name       nvarchar(80)      NULL,
    contact_email      nvarchar(120)     NULL,
    contact_phone      varchar(30)       NULL,
    country_code       char(2)           NOT NULL,
    credit_limit       decimal(12, 2)    NOT NULL,
    account_manager_id int               NULL
        CONSTRAINT fk_customers_account_manager REFERENCES hr.employees (employee_id),
    is_active          bit               NOT NULL,
    created_at         datetimeoffset(0) NOT NULL
);

CREATE UNIQUE INDEX ux_customers_code ON sales.customers (customer_code);

CREATE TABLE sales.products (
    product_id      int IDENTITY(1, 1) NOT NULL CONSTRAINT pk_products PRIMARY KEY,
    sku             varchar(20)  NOT NULL CONSTRAINT uq_products_sku UNIQUE,
    product_name    nvarchar(80) NOT NULL,
    unit_price      money        NOT NULL,
    is_discontinued bit          NOT NULL,
    spec            xml          NULL
);

CREATE TABLE sales.orders (
    order_id     int IDENTITY(1, 1) NOT NULL CONSTRAINT pk_orders PRIMARY KEY,
    order_uid    uniqueidentifier  NOT NULL CONSTRAINT uq_orders_uid UNIQUE,
    customer_id  int               NOT NULL
        CONSTRAINT fk_orders_customer REFERENCES sales.customers (customer_id),
    sales_rep_id int               NULL
        CONSTRAINT fk_orders_sales_rep REFERENCES hr.employees (employee_id),
    ordered_at   datetimeoffset(0) NOT NULL,
    status       varchar(12)       NOT NULL
        CONSTRAINT ck_orders_status CHECK (status IN ('open', 'shipped', 'invoiced', 'cancelled')),
    currency     char(3)           NOT NULL
);

CREATE TABLE sales.order_lines (
    order_id     int            NOT NULL
        CONSTRAINT fk_order_lines_order REFERENCES sales.orders (order_id),
    line_no      smallint       NOT NULL,
    product_id   int            NOT NULL
        CONSTRAINT fk_order_lines_product REFERENCES sales.products (product_id),
    quantity     int            NOT NULL,
    unit_price   decimal(10, 2) NOT NULL,
    discount_pct decimal(5, 2)  NOT NULL CONSTRAINT df_order_lines_discount DEFAULT (0),
    line_total   AS (CAST(quantity * unit_price * (100 - discount_pct) / 100 AS decimal(12, 2))) PERSISTED,
    CONSTRAINT pk_order_lines PRIMARY KEY (order_id, line_no)
);
GO

-- 400 customers: invented company names, a contact each, account managers from sales.
INSERT INTO sales.customers (customer_code, company_name, contact_name, contact_email, contact_phone,
                             country_code, credit_limit, account_manager_id, is_active, created_at)
SELECT
    CONCAT('C', RIGHT(CONCAT('0000000', n.n), 7)),
    CONCAT(w.name, N' ', t.name),
    CONCAT(f.name, N' ', l.name),
    CASE WHEN n.n % 17 = 0 THEN NULL ELSE CONCAT(f.slug, '.', l.slug, '.c', n.n, '@example.com') END,
    CASE (n.n + 2) % 5
        WHEN 0 THEN CONCAT('(', a.code, ') 555-01', p.s)
        WHEN 1 THEN CONCAT(a.code, '-555-01', p.s)
        WHEN 2 THEN CONCAT('+1 ', a.code, ' 555 01', p.s)
        WHEN 3 THEN CONCAT(a.code, '.555.01', p.s)
        ELSE CONCAT(a.code, '55501', p.s)
    END,
    CASE WHEN n.n % 4 = 2 THEN 'CA' ELSE 'US' END,
    CAST(((n.n * 37) % 50 + 1) * 1000 AS decimal(12, 2)),
    2 + 8 * (n.n % 32),
    CASE WHEN n.n % 13 = 0 THEN 0 ELSE 1 END,
    TODATETIMEOFFSET(
        DATEADD(minute, (n.n * 7177) % 1440, DATEADD(day, (n.n * 53) % 2500, CAST('2019-01-01' AS datetime2(0)))),
        CASE n.n % 3 WHEN 0 THEN -300 WHEN 1 THEN -480 ELSE 0 END)
FROM #n AS n
JOIN #word AS w ON w.i = n.n % 12
JOIN #trade AS t ON t.i = (n.n / 12) % 8
JOIN #first AS f ON f.i = (n.n + 5) % 16
JOIN #last AS l ON l.i = (n.n / 16 + 3) % 16
JOIN #area AS a ON a.i = (n.n / 7) % 6
CROSS APPLY (SELECT RIGHT(CONCAT('0', (n.n * 3) % 100), 2) AS s) AS p
WHERE n.n <= 400
ORDER BY n.n;

-- 40 products, each with a small XML specification.
INSERT INTO sales.products (sku, product_name, unit_price, is_discontinued, spec)
SELECT
    CONCAT('LAB-', RIGHT(CONCAT('000', n.n), 4)),
    CONCAT(k.name, N' ', CASE (n.n - 1) / 8 WHEN 0 THEN N'S' WHEN 1 THEN N'M' WHEN 2 THEN N'L'
                                            WHEN 3 THEN N'XL' ELSE N'Pro' END),
    CAST((n.n * 131) % 900 + 25.99 AS money),
    CASE WHEN n.n % 11 = 0 THEN 1 ELSE 0 END,
    CAST(CONCAT('<spec><weightKg>', k.weight_kg * (1 + (n.n - 1) / 8), '</weightKg>',
                '<voltage>', CASE n.n % 3 WHEN 0 THEN '24' WHEN 1 THEN '120' ELSE '230' END, '</voltage>',
                '<warrantyMonths>', 12 * (1 + n.n % 3), '</warrantyMonths></spec>') AS xml)
FROM #n AS n
JOIN #kind AS k ON k.i = (n.n - 1) % 8
WHERE n.n <= 40
ORDER BY n.n;

-- 1,500 orders; Canadian customers (customer_id % 4 = 2) pay in CAD.
INSERT INTO sales.orders (order_uid, customer_id, sales_rep_id, ordered_at, status, currency)
SELECT
    CAST(HASHBYTES('MD5', CONCAT('order-', n.n)) AS uniqueidentifier),
    c.customer_id,
    2 + 8 * (n.n % 32),
    TODATETIMEOFFSET(
        DATEADD(minute, (n.n * 389) % 1440, DATEADD(day, (n.n * 13) % 1460, CAST('2022-01-01' AS datetime2(0)))),
        -300),
    CASE n.n % 10 WHEN 0 THEN 'cancelled' WHEN 1 THEN 'open' WHEN 2 THEN 'open'
                  WHEN 3 THEN 'shipped' WHEN 4 THEN 'shipped' WHEN 5 THEN 'shipped' ELSE 'invoiced' END,
    CASE WHEN c.customer_id % 4 = 2 THEN 'CAD' ELSE 'USD' END
FROM #n AS n
CROSS APPLY (SELECT 1 + (n.n * 7) % 400 AS customer_id) AS c
WHERE n.n <= 1500
ORDER BY n.n;

-- Planted problems: placeholder and mistyped order dates.
UPDATE sales.orders SET ordered_at = '1900-01-01 00:00:00 +00:00' WHERE order_id IN (404, 1212);
UPDATE sales.orders SET ordered_at = '2207-03-14 10:30:00 -05:00' WHERE order_id = 777;

-- 3,000 order lines: one, two or three per order.
INSERT INTO sales.order_lines (order_id, line_no, product_id, quantity, unit_price, discount_pct)
SELECT
    o.order_id,
    l.n,
    pr.product_id,
    1 + (o.order_id + l.n * 5) % 12,
    pr.unit_price,
    CASE WHEN (o.order_id + l.n) % 7 = 0 THEN 10 WHEN (o.order_id + l.n) % 5 = 0 THEN 5 ELSE 0 END
FROM sales.orders AS o
JOIN #n AS l ON l.n <= 1 + o.order_id % 3
JOIN sales.products AS pr ON pr.product_id = 1 + (o.order_id * 3 + l.n * 11) % 40;
GO

CREATE VIEW sales.v_order_totals
AS
SELECT o.order_id,
       o.ordered_at,
       o.status,
       o.currency,
       c.customer_code,
       c.company_name,
       COUNT(*) AS line_count,
       SUM(ol.line_total) AS order_total
FROM sales.orders AS o
JOIN sales.customers AS c ON c.customer_id = o.customer_id
JOIN sales.order_lines AS ol ON ol.order_id = o.order_id
GROUP BY o.order_id, o.ordered_at, o.status, o.currency, c.customer_code, c.company_name;
GO

-- ops --------------------------------------------------------------------------------------------

CREATE TABLE ops.equipment (
    equipment_id     int IDENTITY(1, 1) NOT NULL CONSTRAINT pk_equipment PRIMARY KEY,
    serial_no        varchar(20)   NOT NULL,
    product_id       int           NOT NULL
        CONSTRAINT fk_equipment_product REFERENCES sales.products (product_id),
    customer_id      int           NOT NULL
        CONSTRAINT fk_equipment_customer REFERENCES sales.customers (customer_id),
    installed_on     date          NOT NULL,
    firmware_version varchar(12)   NOT NULL,
    firmware_hash    varbinary(32) NOT NULL,
    last_seen_at     datetime2(0)  NULL
);

CREATE UNIQUE INDEX ux_equipment_serial ON ops.equipment (serial_no);

CREATE TABLE ops.work_orders (
    work_order_id      int IDENTITY(100001, 1) NOT NULL CONSTRAINT pk_work_orders PRIMARY KEY,
    equipment_id       int           NOT NULL
        CONSTRAINT fk_work_orders_equipment REFERENCES ops.equipment (equipment_id),
    customer_id        int           NOT NULL
        CONSTRAINT fk_work_orders_customer REFERENCES sales.customers (customer_id),
    technician_id      int           NULL
        CONSTRAINT fk_work_orders_technician REFERENCES hr.employees (employee_id),
    priority           tinyint       NOT NULL CONSTRAINT ck_work_orders_priority CHECK (priority BETWEEN 1 AND 4),
    status             varchar(12)   NOT NULL,
    opened_at          datetime2(0)  NOT NULL,
    closed_at          datetime2(0)  NULL,
    labour_hours       decimal(5, 2) NULL,
    labour_cost        money         NULL,
    site_contact_name  nvarchar(80)  NULL,
    site_contact_phone varchar(30)   NULL,
    checklist          xml           NULL,
    notes              nvarchar(max) NULL
);

-- A staging table loaded from customer files, with no primary key, as such tables often are.
CREATE TABLE ops.customer_import_staging (
    customer_code varchar(10)   NULL,
    company_name  nvarchar(100) NULL,
    contact_name  nvarchar(80)  NULL,
    contact_email nvarchar(120) NULL,
    contact_phone varchar(30)   NULL,
    source_file   varchar(60)   NOT NULL,
    loaded_at     datetime2(0)  NOT NULL
);
GO

-- 500 pieces of installed equipment.
INSERT INTO ops.equipment (serial_no, product_id, customer_id, installed_on, firmware_version, firmware_hash,
                           last_seen_at)
SELECT
    CONCAT('SN-', 2020 + n.n % 6, '-', RIGHT(CONCAT('0000', n.n), 5)),
    1 + (n.n * 3) % 40,
    1 + (n.n * 11) % 400,
    DATEADD(day, (n.n * 17) % 2190, CAST('2020-01-01' AS date)),
    CONCAT('3.', n.n % 9, '.', n.n % 4),
    HASHBYTES('SHA2_256', CONCAT('firmware-3.', n.n % 9, '.', n.n % 4)),
    CASE WHEN n.n % 29 = 0 THEN NULL
         ELSE DATEADD(minute, -((n.n * 61) % 50000), CAST('2026-10-01T12:00:00' AS datetime2(0))) END
FROM #n AS n
WHERE n.n <= 500
ORDER BY n.n;

-- 1,000 work orders, numbered from 100001; technicians come from field service.
INSERT INTO ops.work_orders (equipment_id, customer_id, technician_id, priority, status, opened_at, closed_at,
                             labour_hours, labour_cost, site_contact_name, site_contact_phone, checklist, notes)
SELECT
    e.equipment_id,
    e.customer_id,
    CASE WHEN n.n % 8 = 0 THEN NULL ELSE 3 + 8 * (n.n % 31) END,
    1 + n.n % 4,
    CASE n.n % 8 WHEN 0 THEN 'new' WHEN 1 THEN 'assigned' WHEN 2 THEN 'in_progress' ELSE 'closed' END,
    o.opened_at,
    CASE WHEN n.n % 8 >= 3 THEN DATEADD(hour, 2 + (n.n * 5) % 70, o.opened_at) END,
    CASE WHEN n.n % 8 >= 3 THEN CAST((1 + (n.n * 3) % 16) / 2.0 AS decimal(5, 2)) END,
    CASE WHEN n.n % 8 >= 3 THEN CAST((1 + (n.n * 3) % 16) * 42.5 AS money) END,
    CONCAT(f.name, N' ', l.name),
    CASE (n.n + 4) % 5
        WHEN 0 THEN CONCAT('(', a.code, ') 555-01', p.s)
        WHEN 1 THEN CONCAT(a.code, '-555-01', p.s)
        WHEN 2 THEN CONCAT('+1 ', a.code, ' 555 01', p.s)
        WHEN 3 THEN CONCAT(a.code, '.555.01', p.s)
        ELSE CONCAT(a.code, '55501', p.s)
    END,
    CAST(CONCAT('<checklist>',
                '<item done="', CASE WHEN n.n % 8 >= 2 THEN 'true' ELSE 'false' END, '">Isolate power</item>',
                '<item done="', CASE WHEN n.n % 8 >= 3 THEN 'true' ELSE 'false' END, '">Inspect seals</item>',
                '<item done="', CASE WHEN n.n % 8 >= 3 THEN 'true' ELSE 'false' END, '">Test run</item>',
                '</checklist>') AS xml),
    CASE n.n % 6
        WHEN 0 THEN N'Customer reports an intermittent fault after start-up.'
        WHEN 1 THEN N'Routine service visit.'
        WHEN 2 THEN N'Replace worn parts and record readings.'
        ELSE NULL
    END
FROM #n AS n
JOIN ops.equipment AS e ON e.equipment_id = 1 + (n.n * 7) % 500
JOIN #first AS f ON f.i = (n.n + 9) % 16
JOIN #last AS l ON l.i = (n.n / 16 + 7) % 16
JOIN #area AS a ON a.i = (n.n / 3) % 6
CROSS APPLY (SELECT RIGHT(CONCAT('0', (n.n * 7) % 100), 2) AS s) AS p
CROSS APPLY (SELECT DATEADD(minute, (n.n * 433) % 1440,
                            DATEADD(day, (n.n * 11) % 1000, CAST('2023-06-01' AS datetime2(0)))) AS opened_at) AS o
WHERE n.n <= 1000
ORDER BY n.n;

-- Planted problem: closed before they were opened.
UPDATE ops.work_orders SET closed_at = DATEADD(day, -3, opened_at)
WHERE work_order_id IN (100013, 100111, 100500, 100875);

-- Staging: every even-numbered customer from the first file, then three of them again from a
-- second file (the duplicate keys).
INSERT INTO ops.customer_import_staging (customer_code, company_name, contact_name, contact_email, contact_phone,
                                         source_file, loaded_at)
SELECT customer_code, company_name, contact_name, contact_email, contact_phone,
       'customers_2026-09-30.csv', CAST('2026-09-30T06:00:00' AS datetime2(0))
FROM sales.customers
WHERE customer_id % 2 = 0
ORDER BY customer_id;

INSERT INTO ops.customer_import_staging (customer_code, company_name, contact_name, contact_email, contact_phone,
                                         source_file, loaded_at)
SELECT customer_code, company_name, contact_name, contact_email, contact_phone,
       'customers_2026-10-01.csv', CAST('2026-10-01T06:00:00' AS datetime2(0))
FROM sales.customers
WHERE customer_id IN (40, 120, 360)
ORDER BY customer_id;
GO

-- Descriptions (MS_Description), saying which tables hold synthetic personal data -------------------

EXEC sys.sp_addextendedproperty @name = N'MS_Description', @value = N'Departments of the company.',
    @level0type = N'SCHEMA', @level0name = N'hr', @level1type = N'TABLE', @level1name = N'departments';
EXEC sys.sp_addextendedproperty @name = N'MS_Description',
    @value = N'One row per employee. Holds synthetic personal data: names, email, phone, date of birth, salary.',
    @level0type = N'SCHEMA', @level0name = N'hr', @level1type = N'TABLE', @level1name = N'employees';
EXEC sys.sp_addextendedproperty @name = N'MS_Description',
    @value = N'Business customers. Holds synthetic personal data: contact name, email and phone.',
    @level0type = N'SCHEMA', @level0name = N'sales', @level1type = N'TABLE', @level1name = N'customers';
EXEC sys.sp_addextendedproperty @name = N'MS_Description', @value = N'Products and spare parts, with an XML specification.',
    @level0type = N'SCHEMA', @level0name = N'sales', @level1type = N'TABLE', @level1name = N'products';
EXEC sys.sp_addextendedproperty @name = N'MS_Description', @value = N'Customer orders.',
    @level0type = N'SCHEMA', @level0name = N'sales', @level1type = N'TABLE', @level1name = N'orders';
EXEC sys.sp_addextendedproperty @name = N'MS_Description', @value = N'Order lines; line_total is computed.',
    @level0type = N'SCHEMA', @level0name = N'sales', @level1type = N'TABLE', @level1name = N'order_lines';
EXEC sys.sp_addextendedproperty @name = N'MS_Description', @value = N'Order totals, one row per order.',
    @level0type = N'SCHEMA', @level0name = N'sales', @level1type = N'VIEW', @level1name = N'v_order_totals';
EXEC sys.sp_addextendedproperty @name = N'MS_Description', @value = N'Equipment installed at customer sites.',
    @level0type = N'SCHEMA', @level0name = N'ops', @level1type = N'TABLE', @level1name = N'equipment';
EXEC sys.sp_addextendedproperty @name = N'MS_Description',
    @value = N'Field-service work orders. Holds synthetic personal data: site contact name and phone.',
    @level0type = N'SCHEMA', @level0name = N'ops', @level1type = N'TABLE', @level1name = N'work_orders';
EXEC sys.sp_addextendedproperty @name = N'MS_Description',
    @value = N'Raw customer file loads, no primary key. Holds synthetic personal data: contact name, email and phone.',
    @level0type = N'SCHEMA', @level0name = N'ops', @level1type = N'TABLE', @level1name = N'customer_import_staging';
GO

-- Sensitivity classifications on the personal-data columns -----------------------------------------

ADD SENSITIVITY CLASSIFICATION TO hr.employees.first_name, hr.employees.last_name
    WITH (LABEL = 'Confidential - GDPR', INFORMATION_TYPE = 'Name', RANK = MEDIUM);
ADD SENSITIVITY CLASSIFICATION TO hr.employees.email, hr.employees.phone
    WITH (LABEL = 'Confidential - GDPR', INFORMATION_TYPE = 'Contact Info', RANK = MEDIUM);
ADD SENSITIVITY CLASSIFICATION TO hr.employees.date_of_birth
    WITH (LABEL = 'Confidential - GDPR', INFORMATION_TYPE = 'Date Of Birth', RANK = HIGH);
ADD SENSITIVITY CLASSIFICATION TO hr.employees.salary
    WITH (LABEL = 'Highly Confidential', INFORMATION_TYPE = 'Financial', RANK = HIGH);
ADD SENSITIVITY CLASSIFICATION TO sales.customers.contact_name, ops.work_orders.site_contact_name,
    ops.customer_import_staging.contact_name
    WITH (LABEL = 'Confidential - GDPR', INFORMATION_TYPE = 'Name', RANK = MEDIUM);
ADD SENSITIVITY CLASSIFICATION TO sales.customers.contact_email, sales.customers.contact_phone,
    ops.work_orders.site_contact_phone, ops.customer_import_staging.contact_email,
    ops.customer_import_staging.contact_phone
    WITH (LABEL = 'Confidential - GDPR', INFORMATION_TYPE = 'Contact Info', RANK = MEDIUM);
GO

-- Done: seed.sh looks for this to know the load finished.
EXEC sys.sp_addextendedproperty @name = N'kilnmark_lab_seed', @value = N'labdb 1: synthetic operations data';
GO

PRINT 'labdb loaded.';
GO
