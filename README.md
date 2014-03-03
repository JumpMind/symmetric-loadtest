SymmetricDS Grinder Load Test
==================
This project demonstrates how you can load test SymmetricDS using [The Grinder] (http://grinder.sourceforge.net) framework.

This example was built as a gradle project and tested from Eclipse.  If you run the `gradle cleanEclipse eclipse` command, the Eclipse project artifacts will be generated and you will be able to import this project as an Eclipse project.


At the root of the project you can find Eclipse shortcuts that launch the Grinder console and up to three agents.  

When running from outside of Eclipse you can install and run agents pointing back to the Grinder console using [instructions] (http://grinder.sourceforge.net/g3/getting-started.html#howtostart) found on the Grinder website.

The load test is driven by the [loadtest.properties] (https://github.com/JumpMind/symmetric-loadtest/raw/master/src/main/console/loadtest.properties) Grinder properties file.  The properties file points to the [loadtest.py] (https://github.com/JumpMind/symmetric-loadtest/raw/master/src/main/console/loadtest.py) load test and contains additional properties that are specific to the SymmetricDS load test.

`loadtst.py` uses [SymmetricProtocolHelper.java] (https://github.com/JumpMind/symmetric-loadtest/raw/master/src/main/java/org/jumpmind/symmetric/loadtest/SymmetricProtocolHelper.java).  The helper contains code that supports the SymmetricDS protocol.

The console home can be found at `src/main/console`.  The console shortcut starts the Grinder console with this directory as the working directory.  All test files are hosted here.  They are deployed over the network to agents using the `Distribute->Distribute Files` menu option.  If running agents from the Eclipse shortcuts, those distributed files will show up in `src/main/agent-n`.

A test can be started from the console menu using `Action->Start Processes`.  `loadtest.py` supports two tests: a push test and a pull test.  By default, these tests are run on each worker thread one after another.  After each test has run the worker thread will sleep for a configurable random amount of time.

The _pull_ test performs a SymmetricDS pull.  It receives batch data from the server and acknowledges it. 

The _push_ test performs a SymmetricDS push.  It uses a configurable template file to generate batch data and pushes it to the server.  The `SymmetricProtocolHelper.java` looks for certain key columns and replaces the data with generated date.  In order to support your tables, you might need to customize this class.

Both the _push_ and _pull_ jobs are configured via the `loadtest.properties` file.  They assume that nodes have been registered on the server.  The load test tool assumes that all 'testable' nodes have the same sym_node_security node_password.  The password itself is configurable.

The example configuration pushes back a SymmetricDS heartbeat (the `sym_node_host` table).  The template file for the batch data is `heartbeat.csv`.  You use the `channel.names` property to configure the channels you want to _push_.  Channels are comma delimited.  Each channel should have a corresponding `{channel name}.csv` file.

Each Grinder agent can support multiple processes which in turn can start multiple threads.  Even though you have multiple agents checking into the console only the number specified by `grinder.agent` will be used during a run.  `grinder.processes` are the number of processes that will be created.  `grinder.threads` is the number of threads each process will start.

Node ids are also specificed in the `loadtest.properties`.  The `locations.agent.id.X.process.id.X` properties contain a list of location ids.  You can customize how node ids are created in `SymmetricProtocolHelper.java`.  The example will randomly select a location id for the agent and process number that is currently doing work.  It will assign a 'workstation id' as part of the node id based on the thread number.  The thread number is zero padded so that node ids look (for this example) look something like: 30444-002.
