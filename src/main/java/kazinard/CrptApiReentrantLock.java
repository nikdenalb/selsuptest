package kazinard;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Alternative version of the class for interacting with the CRPT API.
 * Thread-safe with sliding window rate limiter using ReentrantLock and Condition to avoid busy-waiting.
 */
public class CrptApiReentrantLock {

    private final int requestLimit;
    private final long windowMillis;
    private final Queue<Long> requestTimestamps = new ConcurrentLinkedQueue<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition rateLimitCondition = lock.newCondition();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * @param timeUnit     Time unit for the interval (SECONDS, MINUTES, etc.).
     * @param requestLimit Maximum number of requests in the interval.
     */
    public CrptApiReentrantLock(TimeUnit timeUnit, int requestLimit) {
        this.requestLimit = requestLimit;
        this.windowMillis = timeUnit.toMillis(1);
    }

    /**
     * Creates a document for introducing goods produced in the Russian Federation into circulation.
     *
     * @param document  Document object
     * @param signature Signature (УКЭП)
     * @throws Exception If request or parsing error occurs
     */
    public void createDocument(Document document, String signature) throws Exception {
        lock.lock();
        try {
            long now = System.currentTimeMillis();

            while (!requestTimestamps.isEmpty()) {
                Long oldest = requestTimestamps.peek();
                if (oldest == null || oldest >= now - windowMillis) {
                    break;
                }
                requestTimestamps.poll();
                rateLimitCondition.signalAll();
            }

            while (requestTimestamps.size() >= requestLimit) {
                Long oldest = requestTimestamps.peek();
                if (oldest == null) {
                    break;
                }
                long waitMillis = (oldest + windowMillis) - now;
                if (waitMillis > 0) {
                    rateLimitCondition.await(waitMillis, TimeUnit.MILLISECONDS);
                }
                now = System.currentTimeMillis();

                while (!requestTimestamps.isEmpty()) {
                    Long oldestInLoop = requestTimestamps.peek();
                    if (oldestInLoop == null || oldestInLoop >= now - windowMillis) {
                        break;
                    }
                    requestTimestamps.poll();
                    rateLimitCondition.signalAll();
                }
            }

            requestTimestamps.add(now);

        } finally {
            lock.unlock();
        }

        String documentJson = objectMapper.writeValueAsString(document);
        String base64Document = Base64.getEncoder().encodeToString(documentJson.getBytes(StandardCharsets.UTF_8));

        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("document_format", "MANUAL");
        requestBody.put("product_document", base64Document);
        requestBody.put("type", "LP_INTRODUCE_GOODS");
        requestBody.put("signature", signature);

        String requestBodyJson = objectMapper.writeValueAsString(requestBody);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://ismp.crpt.ru/api/v3/lk/documents/create"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new RuntimeException("API request failed: " + response.statusCode() + ", body: " + response.body());
        }

    }


    public record Description(
            @JsonProperty("participantInn") String participantInn
    ) {
    }

    public record Product(
            @JsonProperty("certificate_document") String certificateDocument,
            @JsonProperty("certificate_document_date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate certificateDocumentDate,
            @JsonProperty("certificate_document_number") String certificateDocumentNumber,
            @JsonProperty("owner_inn") String ownerInn,
            @JsonProperty("producer_inn") String producerInn,
            @JsonProperty("production_date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate productionDate,
            @JsonProperty("tnved_code") String tnvedCode,
            @JsonProperty("uit_code") String uitCode,
            @JsonProperty("uitu_code") String uituCode
    ) {
    }

    public record Document(
            Description description,
            @JsonProperty("doc_id") String docId,
            @JsonProperty("doc_status") String docStatus,
            @JsonProperty("doc_type") String docType,
            @JsonProperty("importRequest") boolean importRequest,
            @JsonProperty("owner_inn") String ownerInn,
            @JsonProperty("participant_inn") String participantInn,
            @JsonProperty("producer_inn") String producerInn,
            @JsonProperty("production_date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate productionDate,
            @JsonProperty("production_type") String productionType,
            List<Product> products,
            @JsonProperty("reg_date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
            LocalDateTime regDate,
            @JsonProperty("reg_number") String regNumber
    ) {
    }
}