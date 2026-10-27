# Checks a Parquet file written by Kilnmark with pyarrow, against the values Kilnmark wrote, given as
# JSON: {"types": [...], "rows": [[...], ...]}, each value as text (or null), with floats and decimals
# in decimal notation, bytes in hex, dates in ISO 8601, times and timestamps in microseconds.
# Prints "ok", or the first difference. Used by FileWriteTest when pyarrow is installed.
import datetime
import decimal
import json
import math
import sys

import pyarrow as pa
import pyarrow.parquet as pq

EPOCH = datetime.datetime(1970, 1, 1)
EPOCH_UTC = datetime.datetime(1970, 1, 1, tzinfo=datetime.timezone.utc)


def micros(delta):
    return delta.days * 86400000000 + delta.seconds * 1000000 + delta.microseconds


def same(want, got, t):
    if want is None or got is None:
        return want is None and got is None
    if pa.types.is_boolean(t):
        return (want == "true") == got
    if pa.types.is_integer(t):
        return int(want) == got
    if pa.types.is_floating(t):
        w = float(want)
        return (math.isnan(w) and math.isnan(got)) or w == got
    if pa.types.is_decimal(t):
        return decimal.Decimal(want) == got
    if pa.types.is_string(t):
        return want == got
    if pa.types.is_binary(t):
        return want == got.hex()
    if pa.types.is_date(t):
        return want == got.isoformat()
    if pa.types.is_time(t):
        return int(want) == ((got.hour * 60 + got.minute) * 60 + got.second) * 1000000 + got.microsecond
    if pa.types.is_timestamp(t):
        return int(want) == micros(got - (EPOCH_UTC if t.tz else EPOCH))
    return False


f = pq.ParquetFile(sys.argv[1])
expected = json.load(open(sys.argv[2], encoding="utf-8"))
table = f.read()
types = [str(field.type) for field in table.schema]
if types != expected["types"]:
    print("types differ: " + repr(types))
    sys.exit(0)
if table.num_rows != len(expected["rows"]):
    print("rows differ: %d" % table.num_rows)
    sys.exit(0)
if not (f.metadata.created_by or "").startswith("kilnmark version "):
    print("created_by is " + repr(f.metadata.created_by))
    sys.exit(0)
columns = [table.column(i).to_pylist() for i in range(table.num_columns)]
for r, row in enumerate(expected["rows"]):
    for c, want in enumerate(row):
        if not same(want, columns[c][r], table.schema[c].type):
            print("row %d column %s: wrote %r, pyarrow read %r" % (r, table.schema[c].name, want, columns[c][r]))
            sys.exit(0)
print("ok")
