# Business scenarios

The samples in the Library each show one node or one flow mechanism. The **business scenarios**
show how a team at a bank, or another regulated organisation, uses Kilnmark for a whole business
process: several pipelines, the flow that runs them in order, and the part a data steward plays,
reviewing, approving and acting on what the checks find. There are three:

| Scenario | The process | The flow |
| --- | --- | --- |
| [Customer onboarding](#customer-onboarding-a-single-customer-view) | Three systems' customers merged into one golden record each, and a pseudonymised copy for analytics | `sample-onboarding-nightly` |
| [Supplier intake](#supplier-intake-a-partners-file-arrives) | A partner's price feed checked before it is trusted, and loaded only once nothing needs review | `sample-supplier-intake` |
| [Month-end close](#month-end-close-invoice-reconciliation) | Invoices reconciled with payments, region by region, and the month closed | `sample-close-month-end` |

They are in the Library under **Scenarios** (the folder `samples/10-scenarios` of the release), with
the other samples. Like every sample, each starts with its explanation: what it shows, why it
matters, what to look for and what it needs. The data is made up: no row comes from a real bank or
person.

## Before you start

The quickest way is a **Starter Pack install** with the Starter Pack turned on
([A Starter Pack install](install/starter-pack.md)): every scenario pipeline and flow is then in the
Library, reading the sample tables, except `sample-close-month-end`, which runs SQL (below). Without
the Starter Pack, a designer adds the samples with **Add samples** ([The Library](library.md)).

The walk-throughs use the Starter Pack's five example people, made with `kilnmark.sh starter people`
([People and access](people-and-access.md)). What each may do decides who does each step:

| Person | Role | In these scenarios |
| --- | --- | --- |
| Vera Lane, `vera.lane@kilnmark.com` | Viewer | Sees the flows, their runs, what each run wrote and its quality scores, and the catalogue. Sees no values from the data, and no notice's text on a run. |
| Sam Okafor, `sam.okafor@kilnmark.com` | Steward | Runs the flows and pipelines, reviews the rows set aside, marks columns sensitive, adds quality rules and works the issue list. Sees values, with those of columns marked sensitive masked (••••). |
| Dana Reyes, `dana.reyes@kilnmark.com` | Designer | Builds and changes the pipelines and flows, and gives a flow a schedule. Sees values of marked columns masked, like Sam. |
| Priya Shah, `priya.shah@kilnmark.com` | Designer who sees sensitive values | Everything Dana does, with values unmasked, and adds the samples that run SQL. |
| Alex Morgan, `alex.morgan@kilnmark.com` | Admin | Settings, API tokens and the audit log. Sees values masked, and is the only one who can clear a sensitive mark. |

**Notices.** A flow's `notify` node tells whoever follows flow notices, in the app and by email,
among people who use Kilnmark with an API token. People who sign in through Keycloak, as the
example people do, get no notifications yet: they read a notice on the flow's run, which shows its
text to stewards and above ([Notifications and email alerts](notifications.md)).

## Customer onboarding: a single customer view

### The business problem

The bank holds its customers in three places: the CRM, an archive of 2023's CRM records, and the
online shop. KYC checks, complaints and marketing consent all need one current record for each
person. The same person can be in more than one place, with their email typed in different ways,
and some records have no usable email at all. The analytics team wants customer data too, but has
no need to know who anyone is.

### What it does

| Sample | What it does | What it writes, under `samples/scenarios/onboarding/` |
| --- | --- | --- |
| `sample-onboarding-merge` | Reads the three customer tables and puts them together (`union`), marking where each row came from. Tidies each email and name: lower case for the email, spaces trimmed. A `gate` sets aside every row whose email is missing or does not look like an email (`matches_pattern`). A `dedupe` then keeps one row per email: the one created most recently (`duplicates: latest` by `created_at`, [Which row wins](reference-pipeline-nodes.md#which-row-wins)). | `golden_customers.parquet`, the golden record; `quarantined_customers.csv`, the rows set aside; `merged_records.csv`, the older records folded into a golden one |
| `sample-onboarding-pseudonymise` | Reads the golden file through a `gate` that stops the run if a rule fails: its own (one row per email, a source key on each) and the golden file's own quality rules, the ones the steward adds. Then a SHA-256 hash in place of the email and the National Insurance number, an age band in place of the date of birth, and the postcode's area in place of the postcode. It keeps only the columns it lists, so names, phone numbers and full postcodes are left out, and so is any column added to the golden file later. | `analytics_customers.parquet` |
| `sample-onboarding-record` | Counts the golden customers by the system each came from, with the time of the load. | `load_record.csv` |
| `sample-onboarding-nightly` (flow) | Checks that the three tables are there (`check-source`), then runs the merge. If more than `limit_share` (5%) of the rows were set aside, or the merge failed, it sends a notice and stops. Otherwise it runs the pseudonymised copy and the load record, and says it is done. A failure later sends a notice and stops too. | |

The flow runs no SQL, so the Starter Pack adds it. To run it each night, a designer or an admin
gives it a schedule on its **Schedules** tab ([Running pipelines and flows on a
schedule](schedules.md)).

### Who does what

1. **Sam marks the sensitive columns, before the first run.** In the catalogue, Sam opens each
   table the merge reads in the Starter Pack's database, `starter-samples-db`: `crm_customers`,
   `crm_customers_archive_2023` and `shop_customers`. On each one's **Quality** tab, under
   **Sensitive columns**, Sam ticks `email`, `date_of_birth`, and `ni_number` where the table has
   it (`phone` and `postcode` too, if the bank treats them so). Each mark is recorded in the audit
   log. Only an admin can clear one ([ADR 0027](../adr/0027-unmasking-is-a-grant.md)).

   Marks follow the data: what a run writes from a marked column is marked where it is written
   ([What Kilnmark records about what you write](pipelines.md#what-kilnmark-records-about-what-you-write)).
   So the golden file's email, date of birth and National Insurance number are marked, and so are
   the hashes and the age band in the analytics copy, which are worked out from them. Mark before
   the first run, or run the flow again after marking.
2. **Dana looks the pipelines over.** She opens each one from the Library on the canvas, previews
   its nodes, and could change a rule or the threshold. Once the columns are marked, a preview of a
   node after one that works on a marked column, such as the tidy step or the gate, is refused for
   her, saying which node and column ([Preview a node](pipelines.md#preview-a-node)): Priya previews those. Dana gives
   `sample-onboarding-nightly` a schedule, if it is to run each night.
3. **Sam runs the flow.** On the flow's page, **Run now**. The run's diagram shows which way it went:
   on the sample data a little over 1% of the rows are set aside, under the 5% limit, so it goes on
   to the analytics copy and the load record, and the notice says how much was set aside.
4. **Sam reviews the rows that failed.** From the flow's run, Sam opens the merge's pipeline run.
   Its log says what the gate set aside and why, by rule ("has an email", "email looks like one").
   Under **What it wrote**, `quarantined_customers.csv` opens in the catalogue, and its **Quality**
   tab shows its sample rows: the customers with no email, their emails empty, the other marked
   columns masked. Sam takes them to the team that owns the CRM, to correct at the source; the next
   night's run picks the corrections up. `merged_records.csv` shows which records were folded into
   a golden one, as evidence of each merge: on the sample data, repeats inside the CRM, older copies
   of three CRM customers in the 2023 archive, and two CRM customers who are also shop customers,
   whose newer shop record wins.
5. **Sam puts quality rules on the golden file.** On `golden_customers.parquet`'s **Quality** tab,
   Sam adds rules from **Recommended from the profile**, or writes them under **Quality rules**: for
   example **Unique** on `email` and **Not null** on `source_key`, with **Open a task in the
   stewards' issue list** ticked ([Setting data quality rules](quality-rules.md)). The pseudonymised
   copy's gate checks them on every run and stops the copy when one fails
   ([Quality gates](quality-gates.md)). **Run rules now** runs them at once; a rule that fails opens
   a task on the **Issues** page, which Sam resolves once it is put right.
6. **Vera sees the results.** She opens the flow's runs and each pipeline run: how each ended, the
   rows written and the quality score of each file written. In the catalogue she sees the golden
   and analytics files' columns, scores and lineage (which tables each came from). She sees no
   values from the data, and not the notice's text.
7. **Priya sees the values unmasked.** On the golden file's **Quality** tab, under **Sample rows**,
   Priya chooses **Show sample rows** and turns on **Show sensitive values**, which only a designer
   who may see unmasked values is offered. She then sees the emails, dates of birth and National
   Insurance numbers that Sam, Dana and Alex see as ••••.
8. **Alex clears a mark, if the steward agrees.** The age band in the analytics copy is marked,
   because it is worked out from the date of birth. If Sam decides an age band alone says too little
   to identify anyone, Alex, an admin, clears its mark on `analytics_customers.parquet`'s **Quality**
   tab. Clearing a mark is a governance decision, recorded in the audit log. The hashes stay marked:
   anyone who knows an email can hash it and find the person.

## Supplier intake: a partner's file arrives

### The business problem

A supplier sends its price feed as a file. One wrong cost can reach invoices, stock valuations or a
customer's bill, so nothing in the file is trusted until it has been checked: every product must
be one the business knows, every cost must be there and positive, and a cost that moved a long way
from the one agreed must be looked at by a person before it is used.

### What it does

| Sample | What it does | What it writes, under `samples/scenarios/supplier-intake/` |
| --- | --- | --- |
| `sample-supplier-feed-arrives` | Stands in for the supplier: copies its feed into the incoming folder of the file storage, as the supplier's own system would deliver it. In real work this step is the supplier's. | `incoming/supplier_feed.csv` |
| `sample-supplier-check` | Reads the delivered file. Keeps each SKU's latest delivery (`dedupe`, latest by `received_on`). Looks each one up among the products and their agreed costs (two `lookup` nodes that keep rows with no match), and works out the change from the agreed cost in percent. A first `gate` quarantines rows for a product nobody knows and costs that are missing or not positive, and checks the delivered file's own quality rules (`rules_from`). A second `gate` sends costs that moved by more than 20% to price review. | `accepted.parquet`; `quarantined_rows.csv`; `price_review.csv`; `superseded_rows.csv`, the earlier deliveries of a SKU sent twice |
| `sample-supplier-load` | Takes every product's agreed cost and puts the accepted new cost, and its date, in place of each one the check accepted. Rows held for review are not in the accepted file, so they never reach it. Here the cost list is a file; in real work it would be your products table. | `agreed_costs.csv` |
| `sample-supplier-intake` (flow) | Runs the delivery, then waits for the file (`wait-for`, every 30 seconds, for up to 30 minutes). Then it runs the check. If either gate set anything aside (`check.quarantined_share > 0`), it sends a notice for the reviewers and loads nothing. If nothing was set aside, it runs the load and says so. | |

The products table holds a list price, not a cost, so the costs agreed with the supplier are a
sample table of their own, `stock.product_costs`. The check uses gates, not a `route`, for price
review: a gate's share set aside is what the flow reads to decide whether to load.

### Who does what

1. **Sam runs the flow** (or a schedule does). On the sample data the check sets rows aside: two
   unknown products, two costs that are not positive, and five costs that moved by more than 20%.
   So the flow sends its notice for the reviewers, and the load does not run. Sam reads the notice
   on the flow's run.
2. **Sam reviews what was held.** From the flow's run, Sam opens the check's pipeline run: its log
   gives each gate's verdict and each rule's count. Under **What it wrote**, `quarantined_rows.csv`
   and `price_review.csv` open in the catalogue, with their rows on the **Quality** tab:
   `change_pct` beside each new and agreed cost. Sam takes them to the supplier: a SKU sent in the
   wrong shape, a missing price, a price rise the buyers must agree.
3. **Sam tightens the check.** On the delivered file, `samples/scenarios/supplier-intake/incoming/supplier_feed.csv`
   in `starter-samples-files`, Sam adds a rule such as **Matches pattern** on `sku`,
   `^[A-Z]{3}-[0-9]{4}$`. Each delivery replaces the file, and its rules stay. The check's first gate
   reads the file's own rules (`rules_from: feed`), so from the next run a SKU of the wrong shape is
   quarantined by Sam's rule too, with its name in the run's log. No designer has to change the
   pipeline.
4. **Sam approves the rest.** Satisfied that what was held can wait, Sam runs
   `sample-supplier-load` on its page: it loads only what the check accepted, and the rows held stay
   out. The run records that Sam started it. In real work, once the supplier sends a corrected
   file, the flow runs again and, with nothing set aside, loads by itself. (Here the stand-in
   delivers the same feed each time, so it is held again.)
5. **Dana** changes what the business decides, such as the 20% limit on the second gate, and saves
   a new version; every version is kept with who saved it and why.
6. **Vera** sees each run, the rows each file received, and the quality scores, but no costs.

## Month-end close: invoice reconciliation

### The business problem

At the end of each month finance closes the books: what each customer has been invoiced is
reconciled with what they have paid, as at the month's end, and every difference is given to someone
to clear. A difference not cleared comes up again at the next close. The close must
not start before all the month's invoices are out, and the month must not be marked closed unless
every region was reconciled. Each region's team owns its own exceptions.

### What it does

| Sample | What it does | What it writes, under `samples/scenarios/month-end/` |
| --- | --- | --- |
| `sample-close-reconcile` | For one region (its parameter `region`): puts each customer in a region by the area of their postcode. Totals each customer's invoices and payments up to the close (`aggregate`), after matching invoices to customers. Invoices name the customer as `C-00170` and payments as `170`, so the invoice's number is worked out with `split_part` and `cast`. A `join` (`how: full`) keeps customers on either side alone. Each customer is then paid, underpaid, or unmatched (an invoice with no payment, or a payment with no invoice), and a `route` sends each its way. The sample data's card transactions stand in for payments received; payments in another currency are counted, not added. | `paid_<region>.parquet`; `exceptions_<region>.parquet`, the underpaid and unmatched |
| `sample-close-summary` | Puts the four regions' exceptions together: a worklist, largest amount owed first, and a summary by region and kind. | `exceptions_worklist.csv`; `exceptions_summary.csv` |
| `sample-close-month-end` (flow) | Waits until the billing periods table says an open month has all its invoices issued (`wait-for` with a `query`). Then it reconciles each region with `for-each`, two at a time: London, North, South and Scotland. If every region succeeded, it runs the summary, then marks the month closed in the billing periods table with a `sql` node, and says so. Otherwise it says so and closes nothing. | |

The billing periods table, `billing.periods`, is the control table the billing system would keep:
one row a month, whether its invoices are all issued, and whether it is open or closed. On the
sample data September 2026 is open and ready.

A region's book holds the CRM's customers whose postcode area is in that region. An invoice or
payment for a customer the CRM does not know, or a postcode in none of the four regions, is in no
region's book: the sample data has neither, and in real work a check for them belongs before the
close.

### Who does what

1. **Priya adds the flow.** It runs SQL: its `wait-for` node's query and its `sql` node. So only a
   designer who may see unmasked values saves it, on a PostgreSQL source with **Allow SQL** on
   ([ADR 0028](../adr/0028-sql-in-reads-and-flow-actions.md)), and the Starter Pack, which an admin
   turns on, leaves it out. In the **Library**, Priya chooses **Add samples**, the Starter Pack's
   database `starter-samples-db` for `crm-db` and `shop-db`, reading the sample tables, and
   `starter-samples-files` for `files`. The samples already there are left as they are, and the
   flow is added. Each run that waits on the query, and each `sql` node that runs, is in the audit
   log, without the SQL.
2. **Sam runs the flow** on the close day. It finds September ready at once, reconciles the four
   regions, two at a time, writes the worklist and summary, and closes September. The notice says
   how many periods were closed. The run's timeline shows each region's pipeline run under the
   `for-each` node.
3. **Sam works through the exceptions.** `exceptions_worklist.csv`, from the summary's run, lists
   every customer who did not reconcile, largest amount owed first, with their region, what was
   invoiced and received, and how many payments were in another currency. `exceptions_summary.csv`
   gives the count and the amount owed by region and kind, the figures the close is signed off on.
   For each exception Sam finds the invoice a payment was for, chases an underpayment, or passes it
   to the region's team. Kilnmark keeps the lists and the run that made them; the work of clearing
   each exception is done with the customer and the ledger. Sam can add rules on the summary, such
   as **In range** on `owed` with **Open a task**, so a month whose exceptions pass a limit opens a
   task on the **Issues** page.
4. **Vera** sees the run, every region's pipeline run and the rows each wrote, but no amounts.
5. **To run it again** on the sample data, run `sample-tables`, which puts the billing periods table
   back with September open. Otherwise the flow waits for an open month, gives up after an hour,
   and says no month was ready.

## How they ship

The scenarios ship and load as the other samples do: in the Library, through the Starter Pack and
**Add samples**, and through `GET /api/v1/samples`. They read the same placeholders, `crm-db`,
`shop-db` and `files`, and write only under `samples/scenarios/` in the file storage. They need two
sample tables the other samples do not read: `billing.periods` and `stock.product_costs`, created
with the rest by the `sample-tables` pipeline. A pipeline that reads what another writes, such as
the pseudonymised copy reading the golden file, needs that one to have run first; each sample's
**Needs** says which.
