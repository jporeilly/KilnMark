-- Reference lab: an online shop's orders and inventory on MariaDB, for Kilnmark to discover.
-- The MariaDB image runs this once, on the first start (an empty data volume); the rows are the
-- same every time (no random numbers: everything is computed from row numbers). All data is
-- synthetic: names come from the short invented lists below, emails use example.com and phone
-- numbers use the 07700 900000 to 900999 range reserved for drama.
--
-- MySQL types on purpose: UNSIGNED integers, ENUM, SET, JSON, YEAR, TINYINT(1) (BOOLEAN), DECIMAL,
-- DATETIME(3), TIMESTAMP, CHAR, TEXT and BLOB.
--
-- Left in on purpose, for profiling and quality rules to find:
--   * null emails: 16 customers (customer_id divisible by 37)
--   * zero dates: date_of_birth 0000-00-00 for customers 50, 150 and 250 (read as null)
--   * out-of-range dates: ordered_at in 1900 (orders 404 and 1212) and in 2099 (order 777)
--   * a negative stock level: stock.stock_levels for product 13 in warehouse 2 holds -4
--   * stock.supplier_feed has no primary key, and SKUs SKU-00040, SKU-00080 and SKU-00120 appear
--     twice in it
--   * product attributes: JSON with the weight in grams for most, a weight as text ("1.2 kg") for
--     products 7, 70 and 107
--   * shop.order_lines.product_id refers to stock.products across databases with no declared
--     key; shop.orders.customer_id has a declared key to shop.customers
-- Synthetic personal data is in shop.customers (name, email, phone, date of birth, postcode); the
-- table comment says so.

SET NAMES utf8mb4;
SET SESSION sql_mode = 'ALLOW_INVALID_DATES';  -- lets the seed plant zero dates

CREATE DATABASE shop CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE stock CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- Empty, for pipeline writes.
CREATE DATABASE warehouse CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Lab-only logins. kiln_reader is what Kilnmark scans with; kiln_writer writes to warehouse only.
CREATE USER 'kiln_reader'@'%' IDENTIFIED BY 'Lab-Reader-2026';
GRANT SELECT, SHOW VIEW ON shop.* TO 'kiln_reader'@'%';
GRANT SELECT, SHOW VIEW ON stock.* TO 'kiln_reader'@'%';
CREATE USER 'kiln_writer'@'%' IDENTIFIED BY 'Lab-Writer-2026';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, ALTER, INDEX ON warehouse.* TO 'kiln_writer'@'%';

-- ---------------------------------------------------------------- shop

-- The seq_1_to_N tables (MariaDB's sequence engine) need a current database.
USE shop;

CREATE TABLE shop.customers (
    customer_id     INT UNSIGNED NOT NULL AUTO_INCREMENT,
    customer_code   CHAR(8) NOT NULL,
    first_name      VARCHAR(40) NOT NULL,
    last_name       VARCHAR(40) NOT NULL,
    email           VARCHAR(120) NULL,
    phone           VARCHAR(20) NULL,
    date_of_birth   DATE NULL,
    postcode        VARCHAR(10) NULL,
    is_active       TINYINT(1) NOT NULL DEFAULT 1,
    marketing_opt_in BOOLEAN NOT NULL DEFAULT 0,
    loyalty_tier    ENUM('bronze', 'silver', 'gold', 'platinum') NOT NULL DEFAULT 'bronze',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (customer_id),
    UNIQUE KEY uq_customer_code (customer_code)
) ENGINE=InnoDB COMMENT='Personal data: shop customers with contact details and date of birth';

INSERT INTO shop.customers (customer_id, customer_code, first_name, last_name, email, phone, date_of_birth,
                            postcode, is_active, marketing_opt_in, loyalty_tier, created_at)
SELECT n,
       CONCAT('C', LPAD(n, 7, '0')),
       ELT(1 + n % 12, 'Ada', 'Bram', 'Cleo', 'Dev', 'Elin', 'Finn', 'Greta', 'Hugo', 'Isla', 'Joss', 'Kai', 'Liv'),
       ELT(1 + (n DIV 12) % 10, 'Arden', 'Brookfield', 'Carrow', 'Dunmore', 'Eastwick', 'Fallow', 'Glenholt',
           'Harrowby', 'Ivelstone', 'Juniper'),
       IF(n % 37 = 0, NULL, CONCAT('customer', n, '@example.com')),
       CONCAT('07700 900', LPAD(n % 1000, 3, '0')),
       IF(n IN (50, 150, 250), '0000-00-00', DATE_ADD('1950-01-01', INTERVAL (n * 97) % 18000 DAY)),
       CONCAT(ELT(1 + n % 6, 'AB', 'CD', 'EF', 'GH', 'JK', 'LM'), 1 + n % 20, ' ', 1 + n % 9,
              ELT(1 + n % 5, 'AA', 'BD', 'EF', 'HJ', 'XY')),
       n % 11 <> 0,
       n % 3 = 0,
       ELT(1 + (n % 10 DIV 4), 'bronze', 'silver', 'gold') ,
       TIMESTAMP('2023-01-01 09:00:00') + INTERVAL n * 7 HOUR
FROM (SELECT CAST(seq AS SIGNED) AS n FROM seq_1_to_600) AS s;
UPDATE shop.customers SET loyalty_tier = 'platinum' WHERE customer_id % 50 = 0;

CREATE TABLE shop.orders (
    order_id        BIGINT UNSIGNED NOT NULL,
    customer_id     INT UNSIGNED NOT NULL,
    ordered_at      DATETIME(3) NOT NULL,
    status          ENUM('placed', 'paid', 'shipped', 'delivered', 'cancelled', 'returned') NOT NULL,
    channel         SET('web', 'app', 'phone', 'marketplace') NOT NULL,
    gift_wrap       TINYINT(1) NOT NULL DEFAULT 0,
    shipping_fee    DECIMAL(7,2) UNSIGNED NOT NULL,
    notes           TEXT NULL,
    PRIMARY KEY (order_id),
    KEY ix_orders_customer (customer_id),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES shop.customers (customer_id)
) ENGINE=InnoDB COMMENT='Orders placed in the shop';

INSERT INTO shop.orders (order_id, customer_id, ordered_at, status, channel, gift_wrap, shipping_fee, notes)
SELECT n,
       1 + (n * 7) % 600,
       CASE n WHEN 404 THEN '1900-01-01 00:00:00' WHEN 1212 THEN '1900-01-01 00:00:00'
              WHEN 777 THEN '2099-12-31 23:59:59'
              ELSE TIMESTAMP('2024-01-01 08:00:00') + INTERVAL n * 263 MINUTE END,
       ELT(1 + n % 6, 'placed', 'paid', 'shipped', 'delivered', 'delivered', IF(n % 4 = 0, 'returned', 'cancelled')),
       ELT(1 + n % 5, 'web', 'app', 'web,app', 'phone', 'marketplace'),
       n % 9 = 0,
       ELT(1 + n % 4, 0.00, 3.95, 4.95, 9.99),
       IF(n % 25 = 0, CONCAT('Leave with neighbour at number ', n % 40), NULL)
FROM (SELECT CAST(seq AS SIGNED) AS n FROM seq_1_to_2000) AS s;

CREATE TABLE shop.order_lines (
    order_id        BIGINT UNSIGNED NOT NULL,
    line_no         TINYINT UNSIGNED NOT NULL,
    product_id      SMALLINT UNSIGNED NOT NULL,
    quantity        SMALLINT UNSIGNED NOT NULL,
    unit_price      DECIMAL(9,2) NOT NULL,
    PRIMARY KEY (order_id, line_no),
    CONSTRAINT fk_lines_order FOREIGN KEY (order_id) REFERENCES shop.orders (order_id)
) ENGINE=InnoDB COMMENT='Order lines; product_id is a product in stock.products';

INSERT INTO shop.order_lines (order_id, line_no, product_id, quantity, unit_price)
SELECT 1 + (n - 1) DIV 2,
       1 + (n - 1) % 2,
       1 + (n * 13) % 120,
       1 + n % 4,
       ROUND(2.5 + ((n * 13) % 120) * 1.75, 2)
FROM (SELECT CAST(seq AS SIGNED) AS n FROM seq_1_to_4000) AS s;

CREATE VIEW shop.v_order_totals AS
SELECT o.order_id, o.customer_id, o.ordered_at, o.status,
       SUM(l.quantity * l.unit_price) + o.shipping_fee AS order_total
FROM shop.orders o JOIN shop.order_lines l ON l.order_id = o.order_id
GROUP BY o.order_id, o.customer_id, o.ordered_at, o.status, o.shipping_fee;

-- ---------------------------------------------------------------- stock

CREATE TABLE stock.warehouses (
    warehouse_id    TINYINT UNSIGNED NOT NULL,
    name            VARCHAR(40) NOT NULL,
    region          ENUM('north', 'south', 'east', 'west') NOT NULL,
    opened          YEAR NOT NULL,
    PRIMARY KEY (warehouse_id)
) ENGINE=InnoDB COMMENT='Warehouses that hold stock';

INSERT INTO stock.warehouses VALUES
    (1, 'Northgate depot', 'north', 2009),
    (2, 'Southmere hub', 'south', 2014),
    (3, 'Eastholm store', 'east', 2019),
    (4, 'Westbrook yard', 'west', 2022);

CREATE TABLE stock.products (
    product_id      SMALLINT UNSIGNED NOT NULL,
    sku             VARCHAR(12) NOT NULL,
    name            VARCHAR(80) NOT NULL,
    category        ENUM('kitchen', 'garden', 'tools', 'lighting', 'storage') NOT NULL,
    tags            SET('eco', 'bestseller', 'clearance', 'new', 'bulky') NOT NULL DEFAULT '',
    attributes      JSON NOT NULL,
    launch_year     YEAR NULL,
    list_price      DECIMAL(9,2) UNSIGNED NOT NULL,
    discontinued    TINYINT(1) NOT NULL DEFAULT 0,
    image           BLOB NULL,
    PRIMARY KEY (product_id),
    UNIQUE KEY uq_products_sku (sku)
) ENGINE=InnoDB COMMENT='The product catalogue';

INSERT INTO stock.products (product_id, sku, name, category, tags, attributes, launch_year, list_price,
                            discontinued, image)
SELECT n,
       CONCAT('SKU-', LPAD(n, 5, '0')),
       CONCAT(ELT(1 + n % 8, 'Oak', 'Slate', 'Copper', 'Linen', 'Willow', 'Ember', 'Fern', 'Harbour'), ' ',
              ELT(1 + n % 5, 'kettle', 'planter', 'spanner set', 'lamp', 'crate'), ' ', n),
       ELT(1 + n % 5, 'kitchen', 'garden', 'tools', 'lighting', 'storage'),
       CONCAT_WS(',', IF(n % 4 = 0, 'eco', NULL), IF(n % 10 = 0, 'bestseller', NULL), IF(n % 17 = 0, 'clearance', NULL),
                 IF(n > 110, 'new', NULL), IF(n % 5 = 4, 'bulky', NULL)),
       IF(n IN (7, 70, 107), JSON_OBJECT('weight', '1.2 kg', 'colour', 'grey'),
          JSON_OBJECT('weight_g', 150 + (n * 37) % 4000, 'colour',
                      ELT(1 + n % 4, 'grey', 'green', 'copper', 'white'), 'dimensions_mm',
                      JSON_ARRAY(100 + n % 300, 80 + n % 200, 20 + n % 90))),
       IF(n % 30 = 0, NULL, 2015 + n % 11),
       ROUND(2.5 + n * 1.75, 2),
       n % 23 = 0,
       IF(n % 40 = 0, UNHEX(SHA2(CONCAT('image', n), 256)), NULL)
FROM (SELECT CAST(seq AS SIGNED) AS n FROM seq_1_to_120) AS s;

CREATE TABLE stock.stock_levels (
    product_id      SMALLINT UNSIGNED NOT NULL,
    warehouse_id    TINYINT UNSIGNED NOT NULL,
    on_hand         INT NOT NULL,
    reorder_point   SMALLINT UNSIGNED NOT NULL,
    counted_at      DATETIME NOT NULL,
    PRIMARY KEY (product_id, warehouse_id),
    CONSTRAINT fk_levels_product FOREIGN KEY (product_id) REFERENCES stock.products (product_id),
    CONSTRAINT fk_levels_warehouse FOREIGN KEY (warehouse_id) REFERENCES stock.warehouses (warehouse_id)
) ENGINE=InnoDB COMMENT='Stock on hand per product and warehouse';

INSERT INTO stock.stock_levels (product_id, warehouse_id, on_hand, reorder_point, counted_at)
SELECT 1 + (n - 1) DIV 4, 1 + (n - 1) % 4,
       IF(n = 50, -4, (n * 29) % 250),
       10 + n % 40,
       TIMESTAMP('2025-03-01 06:00:00') + INTERVAL n * 11 MINUTE
FROM (SELECT CAST(seq AS SIGNED) AS n FROM seq_1_to_480) AS s;

CREATE TABLE stock.stock_movements (
    movement_id     INT UNSIGNED NOT NULL AUTO_INCREMENT,
    product_id      SMALLINT UNSIGNED NOT NULL,
    warehouse_id    TINYINT UNSIGNED NOT NULL,
    moved_at        DATETIME(3) NOT NULL,
    kind            ENUM('receipt', 'pick', 'transfer', 'adjustment') NOT NULL,
    quantity        INT NOT NULL,
    reference       VARCHAR(20) NULL,
    PRIMARY KEY (movement_id),
    KEY ix_movements_product (product_id),
    CONSTRAINT fk_movements_product FOREIGN KEY (product_id) REFERENCES stock.products (product_id),
    CONSTRAINT fk_movements_warehouse FOREIGN KEY (warehouse_id) REFERENCES stock.warehouses (warehouse_id)
) ENGINE=InnoDB COMMENT='Every receipt, pick, transfer and adjustment';

INSERT INTO stock.stock_movements (movement_id, product_id, warehouse_id, moved_at, kind, quantity, reference)
SELECT n, 1 + (n * 7) % 120, 1 + n % 4,
       TIMESTAMP('2025-01-01 07:00:00') + INTERVAL n * 41 MINUTE,
       ELT(1 + n % 4, 'receipt', 'pick', 'transfer', 'adjustment'),
       CASE n % 4 WHEN 0 THEN 20 + n % 80 WHEN 1 THEN -(1 + n % 6) WHEN 2 THEN 5 + n % 15 ELSE (n % 7) - 3 END,
       IF(n % 4 = 1, CONCAT('ORD-', 1 + n % 2000), NULL)
FROM (SELECT CAST(seq AS SIGNED) AS n FROM seq_1_to_3000) AS s;

-- A supplier's price feed as loaded: no primary key, and three SKUs sent twice.
CREATE TABLE stock.supplier_feed (
    sku             VARCHAR(12) NOT NULL,
    supplier        VARCHAR(40) NOT NULL,
    cost_price      DECIMAL(9,2) NOT NULL,
    lead_time_days  TINYINT UNSIGNED NULL,
    in_stock        TINYINT(1) NULL,
    received_on     DATE NOT NULL
) ENGINE=InnoDB COMMENT='Supplier price feed, loaded as received';

INSERT INTO stock.supplier_feed (sku, supplier, cost_price, lead_time_days, in_stock, received_on)
SELECT CONCAT('SKU-', LPAD(n, 5, '0')),
       ELT(1 + n % 3, 'Brightwell Supplies', 'Calder and Moss', 'Tidewater Trading'),
       ROUND(1.5 + n * 1.05, 2),
       IF(n % 19 = 0, NULL, 2 + n % 21),
       IF(n % 13 = 0, NULL, n % 6 <> 0),
       DATE_ADD('2025-04-01', INTERVAL n % 28 DAY)
FROM (SELECT CAST(seq AS SIGNED) AS n FROM seq_1_to_120) AS s;
INSERT INTO stock.supplier_feed (sku, supplier, cost_price, lead_time_days, in_stock, received_on)
SELECT sku, supplier, cost_price + 0.10, lead_time_days, in_stock, DATE_ADD(received_on, INTERVAL 1 DAY)
FROM stock.supplier_feed WHERE sku IN ('SKU-00040', 'SKU-00080', 'SKU-00120');

-- The cost agreed with the supplier for each product, which a new feed's costs are compared with.
-- Every tenth product's agreed cost is a quarter below the feed's, so the change is over 20%.
CREATE TABLE stock.product_costs (
    sku             VARCHAR(12) NOT NULL,
    agreed_cost     DECIMAL(9,2) NOT NULL,
    agreed_on       DATE NOT NULL,
    PRIMARY KEY (sku)
) ENGINE=InnoDB COMMENT='The cost agreed with the supplier for each product';

INSERT INTO stock.product_costs (sku, agreed_cost, agreed_on)
SELECT CONCAT('SKU-', LPAD(n, 5, '0')),
       ROUND((1.5 + n * 1.05) * IF(n % 10 = 0, 0.75, 1 - (n % 5) / 100), 2),
       DATE_ADD('2025-01-01', INTERVAL n % 60 DAY)
FROM (SELECT CAST(seq AS SIGNED) AS n FROM seq_1_to_120) AS s;

ANALYZE TABLE shop.customers, shop.orders, shop.order_lines, stock.warehouses, stock.products, stock.stock_levels,
    stock.stock_movements, stock.supplier_feed, stock.product_costs;
