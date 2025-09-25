import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class CrptApi {

    private final TimeUnit timeUnit;
    private final int requestLimit;
    private final ConcurrentLinkedQueue<Instant> requestTimestamps;
    private final ReentrantLock lock;
    private final HttpClient httpClient;
    private final String apiUrl;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this(timeUnit, requestLimit, "https://ismp.crpt.ru/api/v3");
    }

    public CrptApi(TimeUnit timeUnit, int requestLimit, String apiUrl) {
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("Request limit must be positive");
        }
        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;
        this.requestTimestamps = new ConcurrentLinkedQueue<>();
        this.lock = new ReentrantLock();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .version(HttpClient.Version.HTTP_2)
            .build();
        this.apiUrl = apiUrl;
    }

    public void createDocument(Document document, String signature) {
        waitForLimit();

        try {
            String requestBody = buildRequestBody(document, signature);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/lk/documents/create"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + getAuthToken())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(30))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("API request failed with status: " + response.statusCode() +
                    ", body: " + response.body());
            }

            String responseBody = response.body();
            if (responseBody.contains("\"value\"")) {
                String documentId = extractDocumentId(responseBody);
                System.out.println("Document created successfully with ID: " + documentId);
            } else {
                throw new RuntimeException("Unexpected response format: " + responseBody);
            }

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to create document", e);
        }
    }

    private void waitForLimit() {
        lock.lock();
        try {
            cleanOldRequests();

            while (requestTimestamps.size() >= requestLimit) {
                try {
                    Instant oldestRequest = requestTimestamps.peek();
                    if (oldestRequest != null) {
                        long timeToWait = timeUnit.toMillis(1) -
                            (System.currentTimeMillis() - oldestRequest.toEpochMilli());
                        if (timeToWait > 0) {
                            Thread.sleep(timeToWait);
                        }
                    }
                    cleanOldRequests();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for rate limit", e);
                }
            }

            requestTimestamps.add(Instant.now());
        } finally {
            lock.unlock();
        }
    }

    private void cleanOldRequests() {
        Instant cutoffTime = Instant.now().minusMillis(timeUnit.toMillis(1));
        while (!requestTimestamps.isEmpty() && requestTimestamps.peek().isBefore(cutoffTime)) {
            requestTimestamps.poll();
        }
    }

    private String buildRequestBody(Document document, String signature) {
        return """
            {
                "document_format": "MANUAL",
                "product_document": "%s",
                "product_group": "%s",
                "signature": "%s",
                "type": "LP_INTRODUCE_GOODS"
            }
            """.formatted(serializeDocument(document), document.getProductGroup(), signature);
    }

    private String serializeDocument(Document document) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"description\":").append(serializeDescription(document.description())).append(",");
        sb.append("\"doc_id\":\"").append(escapeJson(document.docId())).append("\",");
        sb.append("\"doc_status\":\"").append(escapeJson(document.docStatus())).append("\",");
        sb.append("\"doc_type\":\"").append(escapeJson(document.docType())).append("\",");
        sb.append("\"importRequest\":").append(document.importRequest()).append(",");
        sb.append("\"owner_inn\":\"").append(escapeJson(document.ownerInn())).append("\",");
        sb.append("\"participant_inn\":\"").append(escapeJson(document.participantInn())).append("\",");
        sb.append("\"producer_inn\":\"").append(escapeJson(document.producerInn())).append("\",");
        sb.append("\"production_date\":\"").append(escapeJson(document.productionDate())).append("\",");
        sb.append("\"production_type\":\"").append(escapeJson(document.productionType())).append("\",");
        sb.append("\"products\":").append(serializeProducts(document.products())).append(",");
        sb.append("\"reg_date\":\"").append(escapeJson(document.regDate())).append("\",");
        sb.append("\"reg_number\":\"").append(escapeJson(document.regNumber())).append("\"");
        sb.append("}");
        return sb.toString();
    }

    private String serializeDescription(Description description) {
        if (description == null) return "null";
        return "{\"participantInn\":\"" + escapeJson(description.participantInn()) + "\"}";
    }

    private String serializeProducts(Product[] products) {
        if (products == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < products.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(serializeProduct(products[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    private String serializeProduct(Product product) {
        if (product == null) return "{}";
        return """
            {
                "certificate_document": "%s",
                "certificate_document_date": "%s",
                "certificate_document_number": "%s",
                "owner_inn": "%s",
                "producer_inn": "%s",
                "production_date": "%s",
                "tnved_code": "%s",
                "uit_code": "%s",
                "uitu_code": "%s"
            }
            """.formatted(
            escapeJson(product.certificateDocument()),
            escapeJson(product.certificateDocumentDate()),
            escapeJson(product.certificateDocumentNumber()),
            escapeJson(product.ownerInn()),
            escapeJson(product.producerInn()),
            escapeJson(product.productionDate()),
            escapeJson(product.tnvedCode()),
            escapeJson(product.uitCode()),
            escapeJson(product.uituCode())
        );
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\f", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private String extractDocumentId(String responseBody) {
        int valueIndex = responseBody.indexOf("\"value\"");
        if (valueIndex == -1) return "unknown";

        int colonIndex = responseBody.indexOf(":", valueIndex);
        int quoteStart = responseBody.indexOf("\"", colonIndex + 1);
        int quoteEnd = responseBody.indexOf("\"", quoteStart + 1);

        if (quoteStart != -1 && quoteEnd != -1) {
            return responseBody.substring(quoteStart + 1, quoteEnd);
        }
        return "unknown";
    }

    private String getAuthToken() {
        // Реализация аутентификации
        // На данный момент возвращаем заглушку
        return "placeholder_token";
    }

    public record Document(
        Description description,
        String docId,
        String docStatus,
        String docType,
        boolean importRequest,
        String ownerInn,
        String participantInn,
        String producerInn,
        String productionDate,
        String productionType,
        Product[] products,
        String regDate,
        String regNumber
    ) {
        public String getProductGroup() {
            // Определить группу продуктов на основе продуктов или других критериев
            return "clothes";
        }
    }

    public record Description(String participantInn) {}

    public record Product(
        String certificateDocument,
        String certificateDocumentDate,
        String certificateDocumentNumber,
        String ownerInn,
        String producerInn,
        String productionDate,
        String tnvedCode,
        String uitCode,
        String uituCode
    ) {}

}

