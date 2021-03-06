package org.radarcns.sink.hdfs;

import org.apache.avro.Schema;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.Test;
import org.radarcns.config.ServerConfig;
import org.radarcns.kafka.ObservationKey;
import org.radarcns.passive.phone.PhoneLight;
import org.radarcns.passive.phone.PhoneSmsUnread;
import org.radarcns.producer.KafkaTopicSender;
import org.radarcns.producer.rest.RestSender;
import org.radarcns.producer.rest.SchemaRetriever;
import org.radarcns.topic.AvroTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertTrue;

public class IntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(IntegrationTest.class);

    @Test(timeout = 120_000L)
    public void integrationTest() throws IOException, InterruptedException {
        RestSender sender = new RestSender.Builder()
                .server(new ServerConfig("http://localhost:8082"))
                .schemaRetriever(new SchemaRetriever(
                        new ServerConfig("http://localhost:8081"), 5))
                .build();

        AvroTopic<ObservationKey, PhoneLight> test1 = new AvroTopic<>("test",
                ObservationKey.getClassSchema(), PhoneLight.getClassSchema(),
                ObservationKey.class, PhoneLight.class);

        Schema updatePhoneSchema = new Schema.Parser().parse(
                "{\"type\":\"record\",\"name\":\"PhoneLight\","
                        + "\"namespace\":\"org.radarcns.passive.phone\","
                        + "\"doc\":\"Data from the light sensor in luminous flux per unit area.\","
                        + "\"fields\":[{\"name\":\"time\",\"type\":\"double\","
                        + "\"doc\":\"Device timestamp in UTC (s).\"},{\"name\":\"timeReceived\","
                        + "\"type\":\"double\",\"doc\":\"Device receiver timestamp in UTC (s).\"},"
                        + "{\"name\":\"light\",\"type\":[\"null\",\"float\"],"
                        + "\"doc\":\"Illuminance (lx).\"}]}");

        AvroTopic<ObservationKey, PhoneLight> test2 = new AvroTopic<>("test",
                ObservationKey.getClassSchema(), updatePhoneSchema,
                ObservationKey.class, PhoneLight.class);

        AvroTopic<ObservationKey, PhoneSmsUnread> test3 = new AvroTopic<>("test",
                ObservationKey.getClassSchema(), PhoneSmsUnread.getClassSchema(),
                ObservationKey.class, PhoneSmsUnread.class);

        try (KafkaTopicSender<ObservationKey, PhoneLight> topicSender = sender.sender(test1)) {
            topicSender.send(new ObservationKey("a", "b", "c"), new PhoneLight(1d, 1d, 1f));
        }
        try (KafkaTopicSender<ObservationKey, PhoneLight> topicSender = sender.sender(test2)) {
            topicSender.send(new ObservationKey("a", "b", "c"), new PhoneLight(1d, 1d, 1f));
        }

        try (KafkaTopicSender<ObservationKey, PhoneSmsUnread> topicSender = sender.sender(test3)) {
            topicSender.send(new ObservationKey("a", "b", "c"), new PhoneSmsUnread(1d, 1d, 1));
        }

        Path path = new Path("hdfs://localhost/topicAndroidNew");

        Configuration conf = new Configuration();
        do {
            Thread.sleep(1000);

            List<String> filePaths = getAllFilePath(path, path.getFileSystem(conf))
                    .stream()
                    .filter(s -> !s.contains("+tmp"))
                    .collect(Collectors.toList());
            if (filePaths.size() >= 3) {
                if (logger.isInfoEnabled()) {
                    logger.info("Paths:\n\t{}", String.join("\n\t", filePaths));
                }
                filePaths.forEach(p -> assertTrue(p.endsWith(".avro")));
                break;
            }
        } while (true);
    }

    /**
     * Recursively lists all non-directory files in given path
     * @param filePath HDFS path to check
     * @param fs file system the path is on.
     * @return list of absolute file path present in given path
     * @throws IOException if a path could not be checked.
     */
    public static List<String> getAllFilePath(Path filePath, FileSystem fs) throws IOException {
        List<String> fileList = new ArrayList<>();
        FileStatus[] fileStatus = fs.listStatus(filePath);
        for (FileStatus fileStat : fileStatus) {
            if (fileStat.isDirectory()) {
                fileList.addAll(getAllFilePath(fileStat.getPath(), fs));
            } else {
                fileList.add(fileStat.getPath().toString());
            }
        }
        return fileList;
    }
}