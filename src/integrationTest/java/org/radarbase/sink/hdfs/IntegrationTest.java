package org.radarbase.sink.hdfs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.stream.Stream;
import okhttp3.Response;
import org.apache.avro.Schema;
import org.apache.avro.SchemaValidationException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.Test;
import org.radarbase.config.ServerConfig;
import org.radarbase.producer.KafkaTopicSender;
import org.radarbase.producer.rest.RestClient;
import org.radarbase.producer.rest.RestSender;
import org.radarbase.producer.rest.RestSender.Builder;
import org.radarbase.producer.rest.SchemaRetriever;
import org.radarbase.topic.AvroTopic;
import org.radarcns.kafka.ObservationKey;
import org.radarcns.passive.phone.PhoneLight;
import org.radarcns.passive.phone.PhoneSmsUnread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(IntegrationTest.class);

    @Test(timeout = 240_000L)
    public void integrationTest()
            throws IOException, InterruptedException, SchemaValidationException {
        RestClient restClient = RestClient.global()
                .server(new ServerConfig("http://localhost:8082"))
                .build();

        RestSender sender = new Builder()
                .httpClient(restClient)
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

        AvroTopic<ObservationKey, PhoneLight> test2 = new AvroTopic<>("test1",
                ObservationKey.getClassSchema(), updatePhoneSchema,
                ObservationKey.class, PhoneLight.class);

        AvroTopic<ObservationKey, PhoneSmsUnread> test3 = new AvroTopic<>("test2",
                ObservationKey.getClassSchema(), PhoneSmsUnread.getClassSchema(),
                ObservationKey.class, PhoneSmsUnread.class);

        for (int i = 0; i < 200; i++) {
            try (Response response = restClient.request("topics")) {
                String responseBody = RestClient.responseBody(response);
                if (response.code() == 200) {
                    if (responseBody == null || responseBody.length() <= 2) {
                        logger.warn("Kafka not ready (no topics available yet)");
                    } else {
                        logger.info("Kafka ready");
                        break;
                    }
                } else {
                    logger.warn("Kafka not ready (HTTP code {}): {}",
                            response.code(), responseBody);
                }
            } catch (IOException ex) {
                logger.error("Kafka not ready (failed to connect): {}", ex.toString());
            }
            Thread.sleep(1_000L);
        }

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

        FileSystem fs = null;
        do {
            Thread.sleep(1000);

            try {
                if (fs == null) {
                    fs = path.getFileSystem(conf);
                }

                long numFiles = getAllFilePath(path, fs)
                        .filter(s -> s.endsWith(".avro"))
                        .count();

                if (numFiles > 0) {
                    logger.info("Paths:\n\t{}", numFiles);
                    if (numFiles >= 3) {
                        break;
                    }
                }
            } catch (Exception ex) {
                logger.error("Failed to get HDFS listing, trying again later: {}", ex.toString());
            }
        } while (true);
    }

    /**
     * Recursively lists all non-directory files in given path
     * @param filePath HDFS path to check
     * @param fs file system the path is on.
     * @return list of absolute file path present in given path
     * @throws java.io.UncheckedIOException if a path could not be checked.
     */
    public static Stream<String> getAllFilePath(Path filePath, FileSystem fs) {
        try {
            return Stream.of(fs.listStatus(filePath))
                    .flatMap(fileStat -> {
                        Path subPath = fileStat.getPath();
                        if (fileStat.isDirectory()) {
                            if (subPath.getName().equals("+tmp")) {
                                return Stream.empty();
                            } else {
                                return getAllFilePath(subPath, fs);
                            }
                        } else {
                            return Stream.of(subPath.toString());
                        }
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
