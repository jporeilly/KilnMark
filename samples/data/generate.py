"""Writes Kilnmark's sample data: small, synthetic tables the samples read.

Every name, address and number here is made up from the lists below with a fixed seed, so the
files are the same on every run. The rows are shaped to show what each sample is about: a few
messy and repeated emails (compute, dedupe), missing emails and unknown segments (the quarantine
gate), orders whose customer is not there (lookup), a couple of shipping fees out of range (the
gate that stops), and repeated or badly shaped SKUs in the supplier feed (the gate that warns).

Run it from the repository root after changing it:  python samples/data/generate.py
It writes samples/data/<schema>/<table>.csv; the server bundles them for the Sample data source.
"""
import csv
import datetime as dt
import json
import os
import random

HERE = os.path.dirname(os.path.abspath(__file__))
R = random.Random(20261030)

FIRST = ["Ada", "Ben", "Cara", "Dev", "Ella", "Finn", "Grace", "Hugo", "Isla", "Jack", "Kira", "Leo", "Maya",
         "Noah", "Orla", "Priya", "Quinn", "Ravi", "Sara", "Tom", "Uma", "Vik", "Wren", "Xander", "Yara", "Zoe",
         "Amir", "Bea", "Cole", "Dina", "Eli", "Fay", "Gus", "Hana", "Ivan", "Jade"]
LAST = ["Smith", "Shaw", "Singh", "Stone", "Sutton", "Adams", "Baker", "Clarke", "Dixon", "Evans", "Fox", "Green",
        "Hughes", "Irwin", "Jones", "Khan", "Lewis", "Moore", "Nash", "Owen", "Patel", "Reid", "Turner", "Walsh",
        "Young", "Ward", "Price", "Scott", "Hale", "Lowe"]
AREAS = ["SW", "N", "E", "LS", "M", "B", "BS", "CF", "EH", "G", "NE", "OX", "CB", "BN", "YO"]
SEGMENTS = ["Retail", "Premier", "Business", "Student"]
MERCHANTS = ["Corner Grocer", "Rail Tickets", "Book Nook", "Coffee Cart", "Fuel Stop", "Home Store", "Gym Club",
             "Pharmacy Plus", "Music Hall", "Garden Centre"]
CATEGORIES = ["Kitchen", "Garden", "Office", "Outdoor", "Lighting", "Storage"]
NOUNS = {"Kitchen": ["Kettle", "Pan", "Knife Set", "Toaster", "Mixing Bowl"],
         "Garden": ["Trowel", "Hose", "Planter", "Shears", "Bird Feeder"],
         "Office": ["Desk Lamp", "Stapler", "Notebook", "Chair Mat", "Pen Pot"],
         "Outdoor": ["Tent", "Lantern", "Flask", "Rucksack", "Camp Stool"],
         "Lighting": ["Bulb Pack", "Floor Lamp", "Fairy Lights", "Wall Light", "Night Light"],
         "Storage": ["Crate", "Shelf Unit", "Box Set", "Hook Rail", "Basket"]}
SUPPLIERS = ["Northwind Supply", "Harbour Goods", "Peak Wholesale", "Riverside Trade"]
STATUSES = ["delivered"] * 6 + ["shipped"] * 3 + ["processing"] * 2 + ["cancelled", "returned"]
CHANNELS = ["web", "app", "store", "phone"]
TIERS = ["bronze"] * 5 + ["silver"] * 3 + ["gold"] * 2 + ["platinum"]


def day(start, end):
    return start + dt.timedelta(days=R.randrange((end - start).days))


def moment(start, end):
    return dt.datetime.combine(day(start, end), dt.time(R.randrange(8, 21), R.randrange(60), R.randrange(60)))


def postcode():
    return f"{R.choice(AREAS)}{R.randrange(1, 20)} {R.randrange(1, 10)}{R.choice('ABDEFGHJLNPQRSTUWXYZ')}" \
           f"{R.choice('ABDEFGHJLNPQRSTUWXYZ')}"


def phone():
    return f"07{R.randrange(100, 999)} {R.randrange(100000, 999999)}"


def money(lo, hi):
    return f"{R.uniform(lo, hi):.2f}"


def write(schema, table, header, rows):
    folder = os.path.join(HERE, schema)
    os.makedirs(folder, exist_ok=True)
    with open(os.path.join(folder, table + ".csv"), "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)


def tz(t):
    return t.strftime("%Y-%m-%dT%H:%M:%S+00:00")


def crm():
    header = ["customer_id", "first_name", "last_name", "email", "phone", "date_of_birth", "postcode",
              "ni_number", "segment", "marketing_opt_in", "created_at"]
    people = []
    for i in range(1, 241):
        first, last = R.choice(FIRST), R.choice(LAST)
        email = f"{first.lower()}.{last.lower()}{i}@example.com"
        segment = R.choice(SEGMENTS)
        people.append([i, first, last, email, phone(), day(dt.date(1950, 1, 1), dt.date(2006, 1, 1)).isoformat(),
                       postcode(), f"QQ{R.randrange(100000, 999999)}{R.choice('ABCD')}", segment,
                       "true" if R.random() < 0.55 else "false",
                       tz(moment(dt.date(2021, 1, 1), dt.date(2026, 9, 1)))])
    # Messy text for compute: spaces around names and capitals in emails.
    for i in range(0, 240, 17):
        people[i][1] = "  " + people[i][1] + " "
        people[i][3] = people[i][3].upper()
    # The same person twice under another id (dedupe): the email differs only in case and spaces.
    for k, i in enumerate(range(5, 200, 17)):
        twin = people[i]
        people[200 + k][1:4] = [twin[1].strip(), twin[2], " " + twin[3].title() + " "]
    # Missing emails and unknown segments: the quarantine gate sets them aside. Under 5% of the rows,
    # so the sample flow that checks the share carries on.
    for i in range(3, 200, 33):
        people[i][3] = ""
    for i, bad in zip(range(11, 240, 60), ["VIP", "Unknown", "retail", "Staff"]):
        people[i][8] = bad
    write("crm", "customers", header, people)

    archive = []
    for i in range(1, 61):
        first, last = R.choice(FIRST), R.choice(LAST)
        archive.append([5000 + i, first, last, f"{first.lower()}.{last.lower()}.{i}@example.org", phone(),
                        day(dt.date(1950, 1, 1), dt.date(2005, 1, 1)).isoformat(), postcode(),
                        f"QQ{R.randrange(100000, 999999)}{R.choice('ABCD')}", R.choice(SEGMENTS),
                        "true" if R.random() < 0.4 else "false",
                        tz(moment(dt.date(2018, 1, 1), dt.date(2023, 12, 31)))])
    write("crm", "customers_archive_2023", header, archive)

    accounts = []
    for a in range(1, 301):
        accounts.append([10000 + a, R.randrange(1, 241), R.choice(["current", "savings", "credit card", "loan"]),
                         day(dt.date(2015, 1, 1), dt.date(2026, 6, 1)).isoformat(),
                         R.choice(["open"] * 8 + ["closed", "frozen"])])
    write("crm", "accounts", ["account_id", "customer_id", "account_type", "opened_on", "status"], accounts)
    return accounts


def billing(accounts):
    txns = []
    for t in range(1, 1201):
        acc = R.choice(accounts)
        txns.append([100000 + t, acc[0], acc[1], money(1, 400), R.choice(["GBP"] * 9 + ["EUR"]),
                     tz(moment(dt.date(2026, 1, 1), dt.date(2026, 10, 1))), R.choice(MERCHANTS),
                     f"****{R.randrange(10000):04d}"])
    write("billing", "transactions", ["txn_id", "account_id", "customer_id", "amount", "currency", "txn_at",
                                      "merchant", "card_last4"], txns)
    invoices = []
    for n in range(1, 401):
        invoices.append([7000 + n, f"C-{R.randrange(1, 241):05d}", day(dt.date(2025, 1, 1), dt.date(2026, 10, 1))
                         .isoformat(), money(20, 2500), "true" if R.random() < 0.8 else "false"])
    write("billing", "invoices", ["invoice_id", "cust_ref", "issued_on", "total", "paid"], invoices)


def shop():
    customers = []
    for i in range(1, 201):
        first, last = R.choice(FIRST), R.choice(LAST)
        customers.append([i, f"S-{i:05d}", first, last, f"{first.lower()}{i}@shop.example.com", phone(),
                          day(dt.date(1950, 1, 1), dt.date(2007, 1, 1)).isoformat(), postcode(),
                          "true" if R.random() < 0.85 else "false", "true" if R.random() < 0.5 else "false",
                          R.choice(TIERS), moment(dt.date(2020, 1, 1), dt.date(2026, 6, 1)).isoformat(" ")])
    write("shop", "customers", ["customer_id", "customer_code", "first_name", "last_name", "email", "phone",
                                "date_of_birth", "postcode", "is_active", "marketing_opt_in", "loyalty_tier",
                                "created_at"], customers)

    orders = []
    for o in range(1, 701):
        # About 2% of orders name a customer who is not in shop.customers (the lookup sets them aside).
        customer = R.randrange(1, 201) if R.random() > 0.02 else R.randrange(900, 950)
        fee = R.choice(["0.00", "2.99", "3.95", "4.99", "7.50", "9.99", "12.00"])
        notes = R.choice([""] * 6 + ["Leave with neighbour", "Gift for a friend", "Call on arrival"])
        orders.append([o, customer, moment(dt.date(2025, 6, 1), dt.date(2026, 10, 1)).isoformat(" "),
                       R.choice(STATUSES), R.choice(CHANNELS), "true" if R.random() < 0.1 else "false", fee, notes])
    # Two fees out of range: the gate that stops the run finds them.
    orders[123][6] = "75.00"
    orders[456][6] = "-1.00"
    write("shop", "orders", ["order_id", "customer_id", "ordered_at", "status", "channel", "gift_wrap",
                             "shipping_fee", "notes"], orders)
    return orders


def stock():
    products = []
    pid = 0
    for cat in CATEGORIES:
        for noun in NOUNS[cat]:
            for size in ["", " Large"]:
                pid += 1
                attrs = {"colour": R.choice(["black", "white", "green", "grey", "blue"]),
                         "weight_kg": round(R.uniform(0.1, 8), 1)}
                products.append([pid, f"{cat[:3].upper()}-{1000 + pid}", noun + size, cat,
                                 ";".join(R.sample(["new", "eco", "bestseller", "gift", "sale"], R.randrange(0, 3))),
                                 json.dumps(attrs), R.randrange(2015, 2027), money(3, 180),
                                 "true" if R.random() < 0.08 else "false"])
    write("stock", "products", ["product_id", "sku", "name", "category", "tags", "attributes", "launch_year",
                                "list_price", "discontinued"], products)

    feed = []
    for p in products:
        feed.append([p[1], R.choice(SUPPLIERS), money(1, float(p[7]) * 0.7 + 1), R.randrange(1, 30),
                     "true" if R.random() < 0.85 else "false", day(dt.date(2026, 8, 1), dt.date(2026, 10, 1))
                     .isoformat()])
    # What the gate that warns finds: a repeated SKU, prices that are not positive, SKUs of the wrong shape.
    feed.append(list(feed[4]))
    feed.append(list(feed[20]))
    # Each repeat is a later delivery at a new price, so which row wins matters (sample-which-row).
    for repeat, weeks in ((feed[-2], 2), (feed[-1], 3)):
        repeat[2] = f"{float(repeat[2]) * 1.1:.2f}"
        repeat[5] = (dt.date.fromisoformat(repeat[5]) + dt.timedelta(weeks=weeks)).isoformat()
    feed[7][2] = "0.00"
    feed[30][2] = "-2.50"
    feed[12][0] = "kit1013"
    feed[40][0] = "GARDEN-1"
    write("stock", "supplier_feed", ["sku", "supplier", "cost_price", "lead_time_days", "in_stock",
                                     "received_on"], feed)
    return products


def lines(orders, products):
    out = []
    for o in orders:
        for n in range(1, R.choice([1, 1, 2, 2, 3, 4]) + 1):
            p = R.choice(products)
            qty = R.choice([1] * 6 + [2, 2, 3, 5, 8])
            out.append([o[0], n, p[0], qty, p[7]])
    write("shop", "order_lines", ["order_id", "line_no", "product_id", "quantity", "unit_price"], out)


def read(schema, table):
    with open(os.path.join(HERE, schema, table + ".csv"), encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def scenarios():
    """The tables the business scenarios (samples/10-scenarios) need besides the others.

    Made last, from the files already written and with a random generator of their own, so the
    tables above stay exactly as they were.
    """
    r = random.Random(20261109)
    # The cost agreed with the supplier for each product, before the latest feed. Most of the feed's
    # latest costs are within a few percent of it; a few have moved by more than 20%, for the steward
    # to review before they are trusted.
    latest = {}
    for row in read("stock", "supplier_feed"):
        if row["sku"] not in latest or row["received_on"] > latest[row["sku"]]["received_on"]:
            latest[row["sku"]] = row
    big = {"KIT-1004": 0.35, "GAR-1014": -0.28, "OFF-1023": 0.42, "OUT-1032": 0.25, "LIG-1046": -0.31}
    costs = []
    for p in read("stock", "products"):
        feed = latest.get(p["sku"])
        if feed is None or float(feed["cost_price"]) <= 0:
            agreed = float(p["list_price"]) * 0.4
        else:
            change = big.get(p["sku"], r.uniform(-0.08, 0.08))
            agreed = float(feed["cost_price"]) / (1 + change)
        agreed_on = dt.date(2026, 3, 1) + dt.timedelta(days=r.randrange(122))
        costs.append([p["sku"], f"{max(agreed, 0.5):.2f}", agreed_on.isoformat()])
    write("stock", "product_costs", ["sku", "agreed_cost", "agreed_on"], costs)

    # The billing periods: one a month, as the month-end close reads and closes them. September is
    # open, its invoices all issued, so the close can run; the months before are closed.
    periods = []
    for month in range(1, 10):
        first = dt.date(2026, month, 1)
        last = dt.date(2026, month + 1, 1) - dt.timedelta(days=1)
        periods.append([first.strftime("%Y-%m"), first.isoformat(), last.isoformat(), "true",
                        "open" if month == 9 else "closed"])
    write("billing", "periods", ["period", "first_day", "last_day", "invoices_issued", "status"], periods)


if __name__ == "__main__":
    accounts = crm()
    billing(accounts)
    orders = shop()
    products = stock()
    lines(orders, products)
    scenarios()
    print("Wrote the sample data under", HERE)
