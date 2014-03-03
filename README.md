SymmetricDS Grinder Load Test
==================
This project demonstrates how you can load test SymmetricDS using [The Grinder] (http://grinder.sourceforge.net) framework.

This example was built as a gradle project and tested from Eclipse.  There are Eclipse shortcuts to launch the Grinder console and up to three agents.  If you run the following command Eclipse project artifacts will be generated and you will be able to import the Eclipse project.

```
gradle cleanEclipse eclipse
```

When running from outside of Eclipse you can install and run agents pointing back to the Grinder console using [instructions] (http://grinder.sourceforge.net/g3/getting-started.html#howtostart) found on the Grinder website.

The load test is driven by the [loadtest.properties] (https://github.com/JumpMind/symmetric-loadtest/raw/master/src/main/console/loadtest.properties) and [loadtest.py] (https://github.com/JumpMind/symmetric-loadtest/raw/master/src/main/console/loadtest.py).
