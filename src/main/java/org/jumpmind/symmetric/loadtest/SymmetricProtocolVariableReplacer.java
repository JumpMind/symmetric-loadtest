package org.jumpmind.symmetric.loadtest;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import net.grinder.common.GrinderProperties;
import net.grinder.script.Grinder.ScriptContext;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.jumpmind.db.util.BinaryEncoding;
import org.jumpmind.symmetric.io.data.Batch;
import org.jumpmind.symmetric.io.data.Batch.BatchType;
import org.jumpmind.symmetric.io.data.CsvData;
import org.jumpmind.symmetric.io.data.DataProcessor;
import org.jumpmind.symmetric.io.data.reader.ProtocolDataReader;
import org.jumpmind.symmetric.io.data.writer.ProtocolDataWriter;
import org.slf4j.Logger;

public class SymmetricProtocolVariableReplacer {

    protected ScriptContext scriptContext;
    protected GrinderProperties properties;
    protected Logger logger;

    protected static ThreadLocal<NodeInfo> nodeInfoByThread = new ThreadLocal<NodeInfo>();
    protected static Map<String, String> templates = new HashMap<String, String>();

    protected int returnsCount = 0;

    public SymmetricProtocolVariableReplacer(ScriptContext scriptContext) {
        this.scriptContext = scriptContext;
        this.properties = scriptContext.getProperties();
        this.logger = scriptContext.getLogger();
    }

    protected String getTemplate(String name) {
        if (!templates.containsKey(name)) {
            String template;
            try {
                template = FileUtils.readFileToString(new File(name));
                templates.put(name, template);
            } catch (IOException e) {
                logger.error("Could not find template for {}", name);
                templates.put(name, "");
            }
        }
        return templates.get(name);
    }

    protected NodeInfo getNodeInfo() {
        String storeId = getLocationId();
        String workstationId = getWorkstationId();

        NodeInfo nodeInfo = nodeInfoByThread.get();
        if (nodeInfo == null) {
            nodeInfo = new NodeInfo();
            String nodeId = storeId + "-" + workstationId;
            nodeInfo.nodeId = nodeId;
            nodeInfo.currentBatchId = properties.getLong("batch.id.start", 42);
            nodeInfoByThread.set(nodeInfo);
        }
        nodeInfo.currentLocationId = storeId;
        nodeInfo.currentWorkstationId = workstationId;
        return nodeInfo;

    }

    public String getLocationPropertyKey() {
        return "locations.agent.id."
                + this.scriptContext.getAgentNumber()
                + ".process.id."
                + (this.scriptContext.getProcessNumber() - this.scriptContext
                        .getFirstProcessNumber());
    }

    public String[] getLocationIds() {
        String locationPropertyKey = getLocationPropertyKey();
        String locationString = properties.getProperty(locationPropertyKey, "");
        if (StringUtils.isNotBlank(locationString)) {
            String[] location = locationString.split(",");
            if (location != null) {
                return location;
            }
        }
        return new String[0];
    }

    protected String getLocationId() {
        String locationId = null;
        logger.info("Looking up location using the key: {}", getLocationPropertyKey());
        String[] locations = getLocationIds();
        if (locations != null && locations.length > 0) {
            locationId = locations[new Random(System.currentTimeMillis()).nextInt(locations.length)];
        }

        logger.warn("The location chosen was {}", locationId);
        return locationId;
    }

    protected String getWorkstationId() {
        return String.format(
                "%03d",
                this.scriptContext.getThreadNumber()
                        + this.properties.getInt("workstation.id.first", 2));
    }

    public String getNodeId() {
        return getNodeInfo().nodeId;
    }

    public byte[] generate() {
        int numberOfBatches = getRandomNumber("max.number.of.batches");
        StringBuilder csv = new StringBuilder();
        String[] channels = properties.getProperty("channel.names", "default").split(",");
        for (int i = 0; i < numberOfBatches; i++) {
            for (String channelId : channels) {
                new BatchBuilder(channelId).build(csv);
            }
        }
        return replace(csv.toString().getBytes());
    }

    public byte[] replace(byte[] input) {
        try {
            NodeInfo nodeInfo = getNodeInfo();
            StringReader reader = new StringReader(new String(input));
            StringWriter writer = new StringWriter();
            ProtocolDataReader protocolReader = new ProtocolDataReader(BatchType.EXTRACT,
                    properties.getProperty("target.node.id"), reader);
            ProtocolDataWriter protocolWriter = buildProtocolDataWriter(nodeInfo, writer);
            DataProcessor processor = new DataProcessor(protocolReader, protocolWriter, "loadtest");
            processor.process();
            String data = writer.getBuffer().toString();
            return data.getBytes();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected ProtocolDataWriter buildProtocolDataWriter(final NodeInfo nodeInfo,
            StringWriter writer) {

        return new ProtocolDataWriter(nodeInfo.nodeId, writer, false) {

            protected String currentTransactionId = null;

            @Override
            public void start(Batch batch) {
                batch.setBatchId(nodeInfo.currentBatchId++);
                super.start(batch);
            }

            @Override
            public void write(CsvData data) {
                try {
                    String ts = new Timestamp(System.currentTimeMillis()).toString();
                    swap("HEARTBEAT_TIME", ts, data);
                    swap("CREATE_TIME", ts, data);
                    swap("NODE_ID", nodeInfo.nodeId, data);
                    swap("ID", currentTransactionId, data);
                    super.write(data);
                } catch (RuntimeException ex) {
                    logger.error("Error while attempt to replace variables", ex);
                    throw ex;
                }
            }

            protected void swap(String column, String value, CsvData data) {
                swap(column, value, data, CsvData.ROW_DATA);
                swap(column, value, data, CsvData.OLD_DATA);
                swap(column, value, data, CsvData.PK_DATA);
            }

            protected void swap(String column, String value, CsvData data, String key) {
                String[] parsedData = data.getParsedData(key);
                if (parsedData != null) {
                    int index = -1;
                    if (key.equals(CsvData.PK_DATA)) {
                        index = table.getPrimaryKeyColumnIndex(column);
                    } else {
                        index = table.getColumnIndex(column);
                    }
                    if (index >= 0 && parsedData.length > index) {
                        parsedData[index] = value;
                        data.removeCsvData(key);
                    }
                }

            }
        };
    }

    protected int getRandomNumber(String property) {
        int number = properties.getInt(property, 1);
        if (number > 1) {
            number = new Random().nextInt(number);
            if (number == 0) {
                number = 1;
            }
        }
        return number;
    }

    class NodeInfo {
        String currentWorkstationId;
        String currentLocationId;
        String nodeId;
        Map<String, Integer> transactionIds = new HashMap<String, Integer>();
        long currentBatchId;

        protected int nextTransactionId() {
            String key = currentLocationId + "-" + currentWorkstationId;
            Integer id = transactionIds.get(key);
            if (id == null) {
                id = 0;
            }
            id++;
            transactionIds.put(key, id);
            Calendar cal = Calendar.getInstance();
            int dayOfMonth = cal.get(Calendar.DAY_OF_MONTH);
            int runNumber = properties.getInt("transaction.id.run.number", 0);
            return Integer.parseInt(Integer.toString(dayOfMonth) + Integer.toString(runNumber)
                    + String.format("%04d", id));
        }
    }

    class BatchBuilder {
        String channelId;

        public BatchBuilder(String channelId) {
            this.channelId = channelId;
        }

        public void build(StringBuilder csv) {
            int maxNumberInBatch = getRandomNumber("max.number.of.in.batch." + channelId);

            csv.append("nodeid,xxxxx\n");
            csv.append("binary,").append(BinaryEncoding.BASE64).append("\n");
            csv.append("channel,").append(channelId).append("\n");
            csv.append("batch, 1\n");

            for (int i = 0; i < maxNumberInBatch; i++) {
                if (i < maxNumberInBatch) {
                    csv.append(getTemplate(channelId + ".csv"));
                    csv.append("\n");
                }
            }

            csv.append("commit, 1\n");
        }

    }
}
