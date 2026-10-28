-- Reference lab: a parcel carrier's fleet and freight on Oracle Database Free, for Kilnmark to
-- discover. The image runs this once, on the first start (an empty data volume), as SYS; the rows
-- are the same every time (no random numbers: everything is computed from row numbers). All data
-- is synthetic: names come from the short invented lists below, emails use example.com, phone
-- numbers use the 07700 900000 to 900999 range reserved for drama, and licence numbers start with
-- QQ, which is never issued.
--
-- Oracle's own types on purpose: NUMBER with and without precision, NUMBER(*,0), a negative scale,
-- BINARY_DOUBLE, DATE (which keeps a time of day), TIMESTAMP, TIMESTAMP WITH TIME ZONE, INTERVAL,
-- VARCHAR2 in characters and bytes, NVARCHAR2, CHAR, CLOB, BLOB, RAW and BOOLEAN.
--
-- Left in on purpose, for profiling and quality rules to find:
--   * null emails: carriers 7, 14 and 21
--   * out-of-range dates: shipped_on in 1900 (shipments 404 and 1212) and 2099 (shipment 777);
--     delivered before shipped (shipments 13, 113 and 2500)
--   * negative weights: shipments 50, 150 and 250 weigh -1.5 kg
--   * FREIGHT.SCAN_STAGING has no primary key, and tracking numbers TRK0000040, TRK0000080 and
--     TRK0000120 appear twice in it
--   * postcodes written three ways: 'AB1 2CD', 'ab12cd' and 'AB1-2CD'
--   * FREIGHT.SHIPMENTS.DECLARED_VALUE is a NUMBER without precision (read as exact text)
--   * FREIGHT.SHIPMENT_EVENTS.SHIPMENT_ID refers to FREIGHT.SHIPMENTS with no declared key;
--     FREIGHT.SHIPMENTS.VEHICLE_ID has a declared key to FLEET.VEHICLES, across schemas
-- Synthetic personal data is in FLEET.DRIVERS (name, email, phone, date of birth, licence number);
-- its comment says so.

WHENEVER SQLERROR EXIT SQL.SQLCODE
ALTER SESSION SET CONTAINER = FREEPDB1;

-- Two schemas of data, and lab-only logins: KILN_READER is what Kilnmark scans with; WAREHOUSE
-- owns an empty schema that pipelines write to.
CREATE USER fleet IDENTIFIED BY "Lab-Owner-2026" QUOTA UNLIMITED ON users;
CREATE USER freight IDENTIFIED BY "Lab-Owner-2026" QUOTA UNLIMITED ON users;
CREATE USER kiln_reader IDENTIFIED BY "Lab-Reader-2026";
GRANT CREATE SESSION TO kiln_reader;
CREATE USER warehouse IDENTIFIED BY "Lab-Writer-2026" QUOTA UNLIMITED ON users;
GRANT CREATE SESSION, CREATE TABLE TO warehouse;

-- ---------------------------------------------------------------- FLEET

CREATE TABLE fleet.depots (
    depot_id     NUMBER(4) PRIMARY KEY,
    name         VARCHAR2(40 CHAR) NOT NULL,
    region       CHAR(2) NOT NULL,
    opened_on    DATE NOT NULL,
    floor_area   NUMBER(8,-2),
    is_hub       BOOLEAN DEFAULT FALSE NOT NULL
);
COMMENT ON TABLE fleet.depots IS 'Depots parcels pass through';

INSERT INTO fleet.depots
SELECT LEVEL,
       DECODE(MOD(LEVEL, 6), 0, 'Northgate', 1, 'Southmere', 2, 'Eastholm', 3, 'Westbrook', 4, 'Highcross', 'Lowfield')
           || ' ' || LEVEL,
       DECODE(MOD(LEVEL, 4), 0, 'NO', 1, 'SO', 2, 'EA', 'WE'),
       DATE '2008-01-01' + LEVEL * 211,
       12000 + LEVEL * 1500,
       CASE WHEN MOD(LEVEL, 4) = 0 THEN TRUE ELSE FALSE END
FROM DUAL CONNECT BY LEVEL <= 12;

CREATE TABLE fleet.vehicles (
    vehicle_id    NUMBER(6) PRIMARY KEY,
    registration  VARCHAR2(10 BYTE) NOT NULL,
    depot_id      NUMBER(4) NOT NULL REFERENCES fleet.depots (depot_id),
    kind          VARCHAR2(10 CHAR) NOT NULL CHECK (kind IN ('van', 'truck', 'ebike')),
    payload_kg    BINARY_DOUBLE,
    service_every INTERVAL DAY(3) TO SECOND(0),
    telematics_id RAW(8),
    CONSTRAINT uq_vehicles_registration UNIQUE (registration)
);
COMMENT ON TABLE fleet.vehicles IS 'Vans, trucks and e-bikes';

INSERT INTO fleet.vehicles
SELECT LEVEL,
       'LB' || LPAD(MOD(LEVEL * 37, 100), 2, '0') || ' ' || CHR(65 + MOD(LEVEL, 26)) || CHR(65 + MOD(LEVEL * 7, 26))
           || CHR(65 + MOD(LEVEL * 11, 26)),
       1 + MOD(LEVEL, 12),
       DECODE(MOD(LEVEL, 5), 0, 'truck', 1, 'ebike', 'van'),
       DECODE(MOD(LEVEL, 5), 0, 18000, 1, 90, 1200) + MOD(LEVEL, 7) * 0.5,
       NUMTODSINTERVAL(DECODE(MOD(LEVEL, 3), 0, 90, 1, 120, 180), 'DAY'),
       HEXTORAW(LPAD(TO_CHAR(LEVEL * 104729, 'FMXXXXXXXX'), 16, '0'))
FROM DUAL CONNECT BY LEVEL <= 150;

CREATE TABLE fleet.drivers (
    driver_id       NUMBER(6) PRIMARY KEY,
    first_name      VARCHAR2(40 CHAR) NOT NULL,
    last_name       NVARCHAR2(40) NOT NULL,
    email           VARCHAR2(120 CHAR),
    phone           VARCHAR2(20 CHAR),
    date_of_birth   DATE,
    licence_number  CHAR(10),
    depot_id        NUMBER(4) REFERENCES fleet.depots (depot_id),
    hired_at        TIMESTAMP(3),
    notes           CLOB
);
COMMENT ON TABLE fleet.drivers IS 'Personal data: drivers with contact details, date of birth and licence number';
COMMENT ON COLUMN fleet.drivers.licence_number IS 'Driving licence number';

INSERT INTO fleet.drivers
SELECT LEVEL,
       DECODE(MOD(LEVEL, 10), 0, 'Ada', 1, 'Bram', 2, 'Cleo', 3, 'Dev', 4, 'Elin', 5, 'Finn', 6, 'Greta', 7, 'Hugo',
           8, 'Isla', 'Joss'),
       DECODE(MOD(TRUNC(LEVEL / 10), 8), 0, 'Arden', 1, 'Brookfield', 2, 'Carrow', 3, 'Dunmore', 4, 'Eastwick',
           5, 'Fallow', 6, 'Glenholt', 'Ølstad'),
       'driver' || LEVEL || '@example.com',
       '07700 900' || LPAD(MOD(LEVEL, 1000), 3, '0'),
       DATE '1960-01-01' + MOD(LEVEL * 97, 14000),
       'QQ' || LPAD(LEVEL * 7919, 8, '0'),
       1 + MOD(LEVEL, 12),
       TIMESTAMP '2015-03-01 08:00:00' + NUMTODSINTERVAL(LEVEL * 3.5, 'DAY'),
       CASE WHEN MOD(LEVEL, 50) = 0 THEN 'Prefers early shifts. Holds a forklift licence.' END
FROM DUAL CONNECT BY LEVEL <= 300;

-- ---------------------------------------------------------------- FREIGHT

CREATE TABLE freight.carriers (
    carrier_id   NUMBER(4) PRIMARY KEY,
    name         VARCHAR2(60 CHAR) NOT NULL,
    email        VARCHAR2(120 CHAR),
    rating       NUMBER(3,1),
    logo         BLOB
);
COMMENT ON TABLE freight.carriers IS 'Partner carriers for the last mile';

INSERT INTO freight.carriers
SELECT LEVEL,
       DECODE(MOD(LEVEL, 5), 0, 'Brightwell', 1, 'Calder', 2, 'Tidewater', 3, 'Harbourline', 'Moorgate') || ' Couriers '
           || LEVEL,
       CASE WHEN MOD(LEVEL, 7) = 0 THEN NULL ELSE 'ops' || LEVEL || '@example.com' END,
       1 + MOD(LEVEL * 3, 40) / 10,
       CASE WHEN MOD(LEVEL, 10) = 0 THEN HEXTORAW('89504E470D0A1A0A') END
FROM DUAL CONNECT BY LEVEL <= 25;

CREATE TABLE freight.shipments (
    shipment_id     NUMBER(10) PRIMARY KEY,
    tracking_number VARCHAR2(12 CHAR) NOT NULL,
    carrier_id      NUMBER(4) REFERENCES freight.carriers (carrier_id),
    vehicle_id      NUMBER(6),
    shipped_on      DATE NOT NULL,
    delivered_at    TIMESTAMP(6) WITH TIME ZONE,
    weight_kg       NUMBER(7,3),
    declared_value  NUMBER,
    parcels         NUMBER(*,0),
    postcode        VARCHAR2(10 CHAR),
    status          VARCHAR2(12 CHAR) NOT NULL,
    signed_for      BOOLEAN,
    CONSTRAINT uq_shipments_tracking UNIQUE (tracking_number)
);
COMMENT ON TABLE freight.shipments IS 'Shipments, one row each';
COMMENT ON COLUMN freight.shipments.declared_value IS 'Value declared by the sender, in pounds';

INSERT INTO freight.shipments
SELECT LEVEL,
       'TRK' || LPAD(LEVEL, 7, '0'),
       1 + MOD(LEVEL * 7, 25),
       1 + MOD(LEVEL * 13, 150),
       CASE LEVEL WHEN 404 THEN DATE '1900-01-01' WHEN 1212 THEN DATE '1900-01-01' WHEN 777 THEN DATE '2099-12-31'
           ELSE DATE '2025-01-01' + TRUNC(LEVEL / 20) + MOD(LEVEL, 24) / 24 END,
       CASE
           WHEN LEVEL IN (13, 113, 2500) THEN FROM_TZ(CAST(DATE '2024-12-01' AS TIMESTAMP), '+00:00')
           WHEN MOD(LEVEL, 9) = 0 THEN NULL
           ELSE FROM_TZ(CAST(DATE '2025-01-02' + TRUNC(LEVEL / 20) AS TIMESTAMP) + NUMTODSINTERVAL(MOD(LEVEL, 600), 'MINUTE'),
               DECODE(MOD(LEVEL, 3), 0, '+00:00', 1, '+01:00', '-05:00'))
       END,
       CASE WHEN LEVEL IN (50, 150, 250) THEN -1.5 ELSE 0.25 + MOD(LEVEL * 37, 30000) / 1000 END,
       ROUND(5 + MOD(LEVEL * 53, 50000) / 100, MOD(LEVEL, 4)),
       1 + MOD(LEVEL, 3),
       DECODE(MOD(LEVEL, 3), 0, 'AB1 2CD', 1, 'ab12cd', 'AB1-2CD'),
       DECODE(MOD(LEVEL, 5), 0, 'delivered', 1, 'in_transit', 2, 'delivered', 3, 'returned', 'delivered'),
       CASE WHEN MOD(LEVEL, 5) IN (0, 2, 4) THEN CASE WHEN MOD(LEVEL, 2) = 0 THEN TRUE ELSE FALSE END END
FROM DUAL CONNECT BY LEVEL <= 5000;

GRANT REFERENCES ON fleet.vehicles TO freight;
ALTER TABLE freight.shipments ADD CONSTRAINT fk_shipments_vehicle FOREIGN KEY (vehicle_id)
    REFERENCES fleet.vehicles (vehicle_id);

CREATE TABLE freight.shipment_events (
    event_id     NUMBER(12) PRIMARY KEY,
    shipment_id  NUMBER(10) NOT NULL,
    depot_id     NUMBER(4),
    happened_at  TIMESTAMP(6),
    event_type   VARCHAR2(16 CHAR) NOT NULL,
    detail       VARCHAR2(200 CHAR)
);
COMMENT ON TABLE freight.shipment_events IS 'Scans and hand-overs along the way';

INSERT INTO freight.shipment_events
SELECT LEVEL,
       1 + MOD(LEVEL - 1, 5000),
       1 + MOD(LEVEL, 12),
       TIMESTAMP '2025-01-01 06:00:00' + NUMTODSINTERVAL(LEVEL * 7, 'MINUTE'),
       DECODE(MOD(LEVEL, 4), 0, 'collected', 1, 'sorted', 2, 'out_for_delivery', 'delivered'),
       CASE WHEN MOD(LEVEL, 25) = 0 THEN 'Left with neighbour at number ' || MOD(LEVEL, 40) END
FROM DUAL CONNECT BY LEVEL <= 20000;

-- A scanner feed as loaded: no primary key, and three tracking numbers sent twice.
CREATE TABLE freight.scan_staging (
    tracking_number VARCHAR2(12 CHAR),
    scanned_at      TIMESTAMP(0),
    depot_id        NUMBER(4),
    raw_line        VARCHAR2(200 CHAR)
);
COMMENT ON TABLE freight.scan_staging IS 'Scanner feed, loaded as received';

INSERT INTO freight.scan_staging
SELECT 'TRK' || LPAD(LEVEL, 7, '0'),
       TIMESTAMP '2025-04-01 07:00:00' + NUMTODSINTERVAL(LEVEL, 'MINUTE'),
       1 + MOD(LEVEL, 12),
       'TRK' || LPAD(LEVEL, 7, '0') || ';' || (1 + MOD(LEVEL, 12)) || ';OK'
FROM DUAL CONNECT BY LEVEL <= 1000;
INSERT INTO freight.scan_staging
SELECT tracking_number, scanned_at + INTERVAL '1' HOUR, depot_id, raw_line
FROM freight.scan_staging WHERE tracking_number IN ('TRK0000040', 'TRK0000080', 'TRK0000120');

CREATE VIEW freight.late_shipments AS
SELECT s.shipment_id, s.tracking_number, s.shipped_on, s.delivered_at
FROM freight.shipments s
WHERE s.delivered_at IS NULL OR CAST(s.delivered_at AS DATE) > s.shipped_on + 3;

COMMIT;

-- What the reader may see, and statistics, so row counts are known before a scan.
BEGIN
    FOR t IN (SELECT owner, table_name FROM dba_tables WHERE owner IN ('FLEET', 'FREIGHT')) LOOP
        EXECUTE IMMEDIATE 'GRANT SELECT ON "' || t.owner || '"."' || t.table_name || '" TO kiln_reader';
        DBMS_STATS.GATHER_TABLE_STATS(t.owner, t.table_name);
    END LOOP;
END;
/
GRANT SELECT ON freight.late_shipments TO kiln_reader;
