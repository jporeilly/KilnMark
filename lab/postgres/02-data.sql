-- Deterministic synthetic data (same rows on every install).
-- Phone numbers use Ofcom's 07700 900xxx range reserved for fiction; NI numbers use the
-- QQ prefix, which HMRC never issues; emails use example.com/.org/.net.

SELECT setseed(0.42);

CREATE TEMP TABLE first_names(i int, v text);
INSERT INTO first_names
SELECT ordinality, v FROM unnest(ARRAY[
  'Amelia','Oliver','Isla','George','Ava','Noah','Mia','Arthur','Freya','Leo',
  'Lily','Harry','Grace','Oscar','Ella','Jack','Evie','Charlie','Sophia','Theo',
  'Priya','Mohammed','Zara','Yusuf','Chloe','Aidan','Niamh','Callum','Ffion','Rhys'
]) WITH ORDINALITY AS t(v, ordinality);

CREATE TEMP TABLE last_names(i int, v text);
INSERT INTO last_names
SELECT ordinality, v FROM unnest(ARRAY[
  'Smith','Jones','Taylor','Brown','Williams','Wilson','Johnson','Davies','Patel','Robinson',
  'Wright','Thompson','Evans','Walker','White','Roberts','Green','Hall','Khan','Lewis',
  'Clarke','Jackson','Wood','Harris','Edwards','Turner','Martin','Cooper','Hill','Ward'
]) WITH ORDINALITY AS t(v, ordinality);

CREATE TEMP TABLE areas(i int, v text);
INSERT INTO areas
SELECT ordinality, v FROM unnest(ARRAY[
  'SW1A','EC2M','M1','B3','LS1','G2','EH1','CF10','BS1','NE1','L1','S1','NG1','BT1','AB10'
]) WITH ORDINALITY AS t(v, ordinality);

-- 5,000 customers
INSERT INTO crm.customers
SELECT n,
       f.v,
       l.v,
       CASE WHEN random() < 0.012 THEN NULL
            ELSE lower(f.v) || '.' || lower(l.v) || n || '@example.' ||
                 (ARRAY['com','org','net'])[1 + (n % 3)] END,
       '07700 900' || lpad((n % 1000)::text, 3, '0'),
       CASE WHEN random() < 0.038 THEN NULL
            ELSE date '1950-01-01' + (random() * 20000)::int END,
       a.v || ' ' || (1 + (n % 9))::text || chr(65 + (n % 26)) || chr(65 + ((n * 7) % 26)),
       'QQ' || lpad((n * 37 % 1000000)::text, 6, '0') || (ARRAY['A','B','C','D'])[1 + (n % 4)],
       (ARRAY['Retail','Retail','Retail','Premier','Business','Student'])[1 + (n % 6)],
       CASE WHEN random() < 0.147 THEN NULL ELSE random() < 0.4 END,
       timestamptz '2019-01-01' + (random() * 2400) * interval '1 day'
FROM generate_series(1, 5000) AS n
JOIN first_names f ON f.i = 1 + (n * 7 % 30)
JOIN last_names  l ON l.i = 1 + (n * 13 % 30)
JOIN areas       a ON a.i = 1 + (n % 15);

-- 7,500 accounts
INSERT INTO crm.accounts
SELECT n,
       1 + ((n * 2654435761) % 5000),
       (ARRAY['Current','Savings','Credit card','Mortgage'])[1 + (n % 4)],
       date '2019-01-01' + (random() * 2400)::int,
       (ARRAY['Open','Open','Open','Closed','Dormant'])[1 + (n % 5)]
FROM generate_series(1, 7500) AS n;

-- 100,000 transactions; customer_id copied from the account, without a foreign key
INSERT INTO billing.transactions
SELECT n,
       acc.account_id,
       acc.customer_id,
       round((random() * random() * 900 + 1)::numeric, 2),
       'GBP',
       timestamptz '2024-01-01' + (random() * 660) * interval '1 day',
       (ARRAY['Tesco','Sainsbury''s','Boots','Greggs','TfL','Amazon UK','Octopus Energy','Pret',
              'Argos','Waterstones'])[1 + (n % 10)],
       lpad((n * 7919 % 10000)::text, 4, '0')
FROM generate_series(1, 100000) AS n
JOIN crm.accounts acc ON acc.account_id = 1 + ((n::bigint * 40503) % 7500);

-- 20,000 invoices keyed by customer id held as text
INSERT INTO billing.invoices
SELECT n,
       (1 + ((n * 97) % 5000))::text,
       date '2024-01-01' + (random() * 660)::int,
       round((random() * 400 + 5)::numeric, 2),
       random() < 0.9
FROM generate_series(1, 20000) AS n;

-- A stale copy of early customers, for duplicate and ROT discovery
INSERT INTO crm.customers_archive_2023
SELECT * FROM crm.customers WHERE created_at < timestamptz '2023-01-01';

-- On-demand large table for the 10 million-row profiling test:
--   SELECT lab.make_big_table(10000000);
CREATE OR REPLACE FUNCTION lab.make_big_table(row_count bigint) RETURNS bigint
LANGUAGE plpgsql AS $$
BEGIN
    DROP TABLE IF EXISTS billing.transactions_big;
    CREATE TABLE billing.transactions_big AS
    SELECT n AS txn_id,
           1 + (n % 7500) AS account_id,
           round((random() * 900 + 1)::numeric, 2) AS amount,
           timestamptz '2020-01-01' + (random() * 2000) * interval '1 day' AS txn_at
    FROM generate_series(1, row_count) AS n;
    RETURN row_count;
END;
$$;

ANALYZE;
