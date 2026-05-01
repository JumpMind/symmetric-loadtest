package org.jumpmind.symmetric.loadtest;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.jumpmind.db.util.BinaryEncoding;
import org.jumpmind.symmetric.io.data.Batch;
import org.jumpmind.symmetric.io.data.Batch.BatchType;
import org.jumpmind.symmetric.io.data.CsvConstants;
import org.jumpmind.symmetric.io.data.CsvData;
import org.jumpmind.symmetric.io.data.DataProcessor;
import org.jumpmind.symmetric.io.data.reader.ProtocolDataReader;
import org.jumpmind.symmetric.io.data.writer.ProtocolDataWriter;
import org.jumpmind.symmetric.web.WebConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SymmetricProtocolHelper {

    protected Properties properties;
    protected Logger logger = LoggerFactory.getLogger(getClass());
    protected int threadNumber;

    protected static Set<String> locationsInUse = new HashSet<>();
    protected static ThreadLocal<NodeInfo> nodeInfoByThread = new ThreadLocal<>();
    protected static Map<String, String> templates = new HashMap<>();

    public SymmetricProtocolHelper(Properties properties, int threadNumber) {
        this.properties = properties;
        this.threadNumber = threadNumber;
    }

    protected String getTemplate(String name) {
        if (!templates.containsKey(name)) {
            String template;
            try {
                template = FileUtils.readFileToString(new File(name), Charset.defaultCharset());
                templates.put(name, template);
            } catch (IOException e) {
                logger.error("Could not find template for {}", name);
                templates.put(name, "");
            }
        }
        return templates.get(name);
    }

    protected NodeInfo getNodeInfo() {
        NodeInfo nodeInfo = nodeInfoByThread.get();
        if (nodeInfo == null) {
            nodeInfo = new NodeInfo();
            nodeInfo.nodeId = assignNodeId();
            nodeInfo.currentBatchId = Long.parseLong(properties.getProperty("batch.id.start", "42"));
            nodeInfoByThread.set(nodeInfo);
        }
        return nodeInfo;
    }

    public String[] getNodeIds() {
        String nodeIdsStr = properties.getProperty("node.ids", "");
        if (StringUtils.isNotBlank(nodeIdsStr)) {
            return nodeIdsStr.split(",");
        }
        return new String[0];
    }

    protected synchronized String assignNodeId() {
        String[] nodeIds = getNodeIds();
        if (nodeIds.length == 0) {
            throw new RuntimeException("No node IDs configured. Set node.ids in loadtest.properties.");
        }

        boolean uniquePerThread = Boolean.parseBoolean(properties.getProperty("node.id.unique.per.thread", "true"));
        String nodeId = nodeIds[threadNumber % nodeIds.length];

        if (uniquePerThread) {
            if (locationsInUse.contains(nodeId)) {
                for (String id : nodeIds) {
                    if (!locationsInUse.contains(id)) {
                        nodeId = id;
                        break;
                    }
                }
            }
            if (locationsInUse.contains(nodeId)) {
                throw new RuntimeException("Unable to find unique node ID for thread " + threadNumber
                        + ". Add more node IDs to node.ids or set node.id.unique.per.thread=false.");
            }
            locationsInUse.add(nodeId);
        }

        logger.info("Thread {} assigned node ID: {}", threadNumber, nodeId);
        return nodeId;
    }

    public String getNodeId() {
        return getNodeInfo().nodeId;
    }

    public byte[] generateBatchPayload() {
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

    protected byte[] replace(byte[] input) {
        try {
            NodeInfo nodeInfo = getNodeInfo();
            StringReader reader = new StringReader(new String(input));
            StringWriter writer = new StringWriter();
            ProtocolDataReader protocolReader = new ProtocolDataReader(BatchType.EXTRACT,
                    properties.getProperty("target.node.id"), reader);
            ProtocolDataWriter protocolWriter = buildProtocolDataWriter(nodeInfo, writer);
            DataProcessor processor = new DataProcessor(protocolReader, protocolWriter, "loadtest");
            processor.process();
            return writer.getBuffer().toString().getBytes();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, String> generateAck(String batchPayload) {
        Map<String, String> ackParams = new LinkedHashMap<>();
        String nodeId = getNodeId();
        for (String line : StringUtils.split(batchPayload, "\n")) {
            line = line.trim();
            if (line.startsWith(CsvConstants.BATCH)) {
                String batchId = StringUtils.split(line, ",")[1].trim();
                ackParams.put(WebConstants.ACK_BATCH_NAME + batchId, WebConstants.ACK_BATCH_OK);
                ackParams.put(WebConstants.ACK_NODE_ID + batchId, nodeId);
            }
        }
        return ackParams;
    }

    protected ProtocolDataWriter buildProtocolDataWriter(final NodeInfo nodeInfo, StringWriter writer) {
        return new ProtocolDataWriter(nodeInfo.nodeId, writer, false, false, false) {
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
                    swap("ID", String.valueOf(nodeInfo.nextTransactionId()), data);
                    super.write(data);
                } catch (RuntimeException ex) {
                    logger.error("Error replacing variables in batch data", ex);
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
                    int index = key.equals(CsvData.PK_DATA)
                            ? table.getPrimaryKeyColumnIndex(column)
                            : table.getColumnIndex(column);
                    if (index >= 0 && parsedData.length > index) {
                        parsedData[index] = value;
                        data.removeCsvData(key);
                    }
                }
            }
        };
    }

    protected int getRandomNumber(String property) {
        int number = Integer.parseInt(properties.getProperty(property, "1"));
        if (number > 1) {
            number = new Random().nextInt(number);
            if (number == 0) {
                number = 1;
            }
        }
        return number;
    }

    class NodeInfo {
        String nodeId;
        Map<String, Integer> transactionIds = new HashMap<>();
        long currentBatchId;

        protected int nextTransactionId() {
            Integer id = transactionIds.merge(nodeId, 1, Integer::sum);
            int dayOfMonth = Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
            int runNumber = Integer.parseInt(properties.getProperty("transaction.id.run.number", "0"));
            return Integer.parseInt(Integer.toString(dayOfMonth) + runNumber + String.format("%04d", id));
        }
    }

    class BatchBuilder {
        String channelId;

        public BatchBuilder(String channelId) {
            this.channelId = channelId;
        }

        public void build(StringBuilder csv) {
            int maxNumberInBatch = getRandomNumber("max.number.of.rows.in.batch." + channelId);
            csv.append("nodeid,xxxxx\n");
            csv.append("binary,").append(BinaryEncoding.BASE64).append("\n");
            csv.append("channel,").append(channelId).append("\n");
            csv.append("batch, 1\n");
            for (int i = 0; i < maxNumberInBatch; i++) {
                csv.append(getTemplate(channelId + ".csv")).append("\n");
            }
            csv.append("commit, 1\n");
        }
    }
}
