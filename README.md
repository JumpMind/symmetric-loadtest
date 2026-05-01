SymmetricDS JMeter Load Test
============================

This project load tests SymmetricDS push/pull sync using [Apache JMeter](https://jmeter.apache.org).
Each simulated thread acts as a registered SymmetricDS node, running push and pull cycles against a live server.

## Requirements

- Java 11+
- [Apache JMeter 5.6+](https://jmeter.apache.org/download_jmeter.cgi)
- A running SymmetricDS server with pre-registered test nodes

## Setup

### 1. Build the helper JAR

```bash
./gradlew assemble
```

This produces `build/libs/symmetric-loadtest.jar` (a fat JAR containing `SymmetricProtocolHelper` and its dependencies).

### 2. Install the JAR into JMeter

Copy the JAR to JMeter's extension directory:

```bash
cp build/libs/symmetric-loadtest.jar $JMETER_HOME/lib/ext/
```

### 3. Pre-register test nodes on the server

Each node ID listed in `node.ids` must exist in the server database before running the test.
Run the following SQL for each node ID (e.g. `00001`):

```sql
INSERT INTO sym_node (node_id, node_group_id, external_id, sync_enabled)
  VALUES ('00001', 'source', '00001', 1);

INSERT INTO sym_node_security (node_id, node_password, registration_enabled, registration_time, initial_load_enabled)
  VALUES ('00001', 'test', 0, NOW(), 0);
```

Also clear `sym_incoming_batch` between test runs, or set `incoming.batches.record.ok.enabled=false`
on the server to avoid batch ID conflicts.

### 4. Configure the test

Edit the **User Defined Variables** at the top of `src/main/console/loadtest.jmx`, or override
properties on the JMeter command line with `-J`:

| Variable | Default | Description |
|---|---|---|
| `server.url` | `http://localhost:31415` | SymmetricDS server base URL |
| `server.path` | `/sync/server` | Sync servlet path |
| `server.auth.token` | `test` | Node password from `sym_node_security` |
| `target.node.id` | `server` | Server node ID |
| `node.ids` | `00001,...,00006` | Comma-separated list of client node IDs |
| `threads` | `6` | Number of concurrent sync threads |
| `ramp_up_seconds` | `10` | Thread ramp-up time |
| `duration_seconds` | `120` | Test duration |
| `channel.names` | `heartbeat` | Channels to push (comma-separated) |
| `time.between.sync.ms` | `1000` | Delay between push/pull cycles |

### 5. Add channel CSV templates

For each channel in `channel.names`, place a `{channel}.csv` template file in the JMeter working
directory. The included `heartbeat.csv` is used for the default `heartbeat` channel. Column values
`NODE_ID`, `HEARTBEAT_TIME`, `CREATE_TIME`, and `ID` are replaced dynamically at runtime.

To test your own tables, copy the CSV format from an existing SymmetricDS batch and add it as
`{channel}.csv`. You may also need to update `SymmetricProtocolHelper.java` to substitute
additional column values.

## Running the test

**GUI mode** (for setup and debugging):

```bash
$JMETER_HOME/bin/jmeter -t src/main/console/loadtest.jmx
```

**Non-GUI mode** (for actual load testing — much lower overhead):

```bash
$JMETER_HOME/bin/jmeter -n \
  -t src/main/console/loadtest.jmx \
  -l results.csv \
  -e -o report/ \
  -Jthreads=20 \
  -Jduration_seconds=300
```

JMeter will write a `results.csv` file and generate an HTML report in `report/`.

## What the test does

Each thread:
1. Initializes a `SymmetricProtocolHelper` and claims a unique node ID from `node.ids`
2. Loops until the test duration expires:
   - **Pull**: `GET /pull` to receive batch data, then `POST /ack` to acknowledge
   - **Push**: `HEAD /push` to check server availability, then `PUT /push` with a generated batch payload
   - Sleeps for `time.between.sync.ms` milliseconds

The `SymmetricProtocolHelper` generates batch payloads in the SymmetricDS CSV wire format using
the channel template files, so the server processes them as real sync data.

## Customizing for other tables

Replace or add channel CSV template files in the console directory. For example, to push `order`
data, create `order.csv` with a sample SymmetricDS batch row and add `order` to `channel.names`.
If your table has columns that need dynamic values (timestamps, IDs, etc.), extend the `swap()`
logic in `SymmetricProtocolHelper.java`.
