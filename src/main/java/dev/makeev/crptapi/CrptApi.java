package dev.makeev.crptapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Thread-safe клиент API Честного знака с лимитом запросов.
 */
public class CrptApi implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(CrptApi.class.getName());
    private static final String API_URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";
    private static final String FIXED_DOCUMENT_TYPE = "LP_INTRODUCE_GOODS";
    private static final String FIXED_DOCUMENT_FORMAT = "MANUAL";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final int HTTP_CREATED = 201;

    private final BlockingQueue<Runnable> requestQueue = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService scheduler;
    private final ExecutorService executor;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final int requestLimit;
    private final long intervalMillis;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("Request limit должен быть больше 0");
        }
        if (timeUnit == null) {
            throw new IllegalArgumentException("TimeUnit не может быть null");
        }

        this.requestLimit = requestLimit;
        this.intervalMillis = timeUnit.toMillis(1);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> getThread(r, "CrptApi-Scheduler"));
        this.executor = Executors.newSingleThreadExecutor(r -> getThread(r, "CrptApi-Executor"));

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        startDispatcher();
    }

    private static Thread getThread(Runnable r, String name) {
        var thread = new Thread(r, name);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * Создание документа.
     *
     * @param document      Документ
     * @param productGroupCode Код группы продукта
     * @param signature     Подпись
     * @param token         Токен
     * @return {@link Future} с результатом выполнения запроса
    */
    public Future<DocumentResponse> createDocument(Document document, int productGroupCode,
                                                   String signature, String token) {
        if (isShutdown.get()) {
            return CompletableFuture.completedFuture(
                    new DocumentResponse("CLIENT_CLOSED", "API клиент был закрыт", "Клиент API был закрыт"));
        }

        if (document == null || signature == null || token == null) {
            return CompletableFuture.completedFuture(
                    new DocumentResponse("INVALID_PARAMS", "Обязательные параметры не могут быть null",
                            "Обязательные параметры не могут быть null"));
        }

        var future = new CompletableFuture<DocumentResponse>();

        requestQueue.offer(() -> {
            try {
                DocumentResponse response = send(document, productGroupCode, signature, token);
                future.complete(response);
            } catch (Exception e) {
                LOGGER.severe("Ошибка при выполнении запроса: " + e.getMessage());
                future.complete(new DocumentResponse("INTERNAL_ERROR",
                        "Внутренняя ошибка: " + e.getMessage(),
                        "Внутренняя ошибка"));
            }
        });

        return future;
    }

    private DocumentResponse send(Document document, int productGroupCode,
                                  String signature, String token) {
        try {
            var request = buildHttpRequest(document, productGroupCode, signature, token);
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == HTTP_CREATED) {
                try {
                    return objectMapper.readValue(response.body(), DocumentResponse.class);
                } catch (JsonProcessingException e) {
                    return new DocumentResponse("JSON_PARSE_ERROR",
                            "Ошибка при обработке JSON ответа: " + e.getMessage(),
                            "Неверная структура JSON ответа");
                }
            }
            try {
                var jsonNode = objectMapper.readTree(response.body());
                String code = jsonNode.has("code") ?
                        jsonNode.get("code").asText() : String.valueOf(response.statusCode());
                String errorMessage = jsonNode.has("error_message") ?
                        jsonNode.get("error_message").asText() : "HTTP Error " + response.statusCode();
                String description = jsonNode.has("description") ?
                        jsonNode.get("description").asText() : response.body();
                    
                return new DocumentResponse(code, errorMessage, description);
            } catch (Exception parseEx) {
                return new DocumentResponse(
                        String.valueOf(response.statusCode()),
                        "HTTP Error " + response.statusCode(),
                        response.body()
                );
            }
        } catch (JsonProcessingException e) {
            return new DocumentResponse("JSON_BUILD_ERROR",
                    "Ошибка при формировании JSON запроса: " + e.getMessage(),
                    "Неверная структура JSON запроса");
        } catch (Exception e) {
            return new DocumentResponse("NETWORK_ERROR",
                    "Ошибка при выполнении HTTP-запроса: " + e.getMessage(),
                    "Сетевая ошибка");
        }
    }

    private HttpRequest buildHttpRequest(Document document, int productGroupCode,
                                         String signature, String token) throws JsonProcessingException {
        var documentJson = objectMapper.writeValueAsString(document);
        var documentBase64 = Base64.getEncoder().encodeToString(documentJson.getBytes());
        var productGroup = getProductGroup(productGroupCode);

        CreateDocumentRequest requestBody = new CreateDocumentRequest(
                FIXED_DOCUMENT_FORMAT,
                documentBase64,
                productGroup.orElse(null),
                signature,
                FIXED_DOCUMENT_TYPE
        );

        var urlBuilder = new StringBuilder(API_URL);
        productGroup.ifPresent(s -> urlBuilder.append("?pg=").append(s));

        return HttpRequest.newBuilder()
                .uri(URI.create(urlBuilder.toString()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .timeout(HTTP_TIMEOUT)
                .build();
    }

    private static Optional<String> getProductGroup(int productGroupCode) {
        String productGroup;
        switch (productGroupCode) {
            case 1:
                productGroup = "clothes";
                break;
            case 2:
                productGroup = "shoes";
                break;
            case 3:
                productGroup = "tobacco";
                break;
            case 4:
                productGroup = "perfumery";
                break;
            case 5:
                productGroup = "tires";
                break;
            case 6:
                productGroup = "electronics";
                break;
            case 7:
                productGroup = "pharma";
                break;
            case 8:
                productGroup = "milk";
                break;
            case 9:
                productGroup = "bicycle";
                break;
            case 10:
                productGroup = "wheelchairs";
                break;
            default:
                productGroup = null;
        }
        return Optional.ofNullable(productGroup);
    }

    private void startDispatcher() {
        scheduler.scheduleAtFixedRate(() -> {
            if (isShutdown.get()) {
                return;
            }

            for (int i = 0; i < requestLimit && !requestQueue.isEmpty(); i++) {
                var task = requestQueue.poll();
                if (task != null) {
                    executor.submit(task);
                }
            }
        }, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            scheduler.shutdown();
            executor.shutdown();

            try {
                if (!scheduler.awaitTermination(SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
                if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static class CreateDocumentRequest {
        @JsonProperty("document_format")
        private final String documentFormat;

        @JsonProperty("product_document")
        private final String productDocumentBase64;

        @JsonProperty("product_group")
        private final String productGroup;

        @JsonProperty("signature")
        private final String signatureBase64;

        @JsonProperty("type")
        private final String type;

        public CreateDocumentRequest(String documentFormat, String productDocumentBase64,
                                     String productGroup, String signatureBase64, String type) {
            this.documentFormat = documentFormat;
            this.productDocumentBase64 = productDocumentBase64;
            this.productGroup = productGroup;
            this.signatureBase64 = signatureBase64;
            this.type = type;
        }
    }

    public static class Document {
        @JsonProperty("description")
        private Description description;

        @JsonProperty("doc_id")
        private String docId;

        @JsonProperty("doc_status")
        private String docStatus;

        @JsonProperty("doc_type")
        private String docType;

        @JsonProperty("importRequest")
        private Boolean importRequest;

        @JsonProperty("owner_inn")
        private String ownerInn;

        @JsonProperty("participant_inn")
        private String participantInn;

        @JsonProperty("producer_inn")
        private String producerInn;

        @JsonProperty("production_date")
        private String productionDate;

        @JsonProperty("production_type")
        private String productionType;

        @JsonProperty("products")
        private List<Product> products;

        @JsonProperty("reg_date")
        private String regDate;

        @JsonProperty("reg_number")
        private String regNumber;

        public Document() {}

        public Description getDescription() { return description; }
        public void setDescription(Description description) { this.description = description; }

        public String getDocId() { return docId; }
        public void setDocId(String docId) { this.docId = docId; }

        public String getDocStatus() { return docStatus; }
        public void setDocStatus(String docStatus) { this.docStatus = docStatus; }

        public String getDocType() { return docType; }
        public void setDocType(String docType) { this.docType = docType; }

        public Boolean getImportRequest() { return importRequest; }
        public void setImportRequest(Boolean importRequest) { this.importRequest = importRequest; }

        public String getOwnerInn() { return ownerInn; }
        public void setOwnerInn(String ownerInn) { this.ownerInn = ownerInn; }

        public String getParticipantInn() { return participantInn; }
        public void setParticipantInn(String participantInn) { this.participantInn = participantInn; }

        public String getProducerInn() { return producerInn; }
        public void setProducerInn(String producerInn) { this.producerInn = producerInn; }

        public String getProductionDate() { return productionDate; }
        public void setProductionDate(String productionDate) { this.productionDate = productionDate; }

        public String getProductionType() { return productionType; }
        public void setProductionType(String productionType) { this.productionType = productionType; }

        public List<Product> getProducts() { return products; }
        public void setProducts(List<Product> products) { this.products = products; }

        public String getRegDate() { return regDate; }
        public void setRegDate(String regDate) { this.regDate = regDate; }

        public String getRegNumber() { return regNumber; }
        public void setRegNumber(String regNumber) { this.regNumber = regNumber; }
    }

    public static class Description {
        @JsonProperty("participantInn")
        private String participantInn;

        public Description() {}

        public String getParticipantInn() { return participantInn; }
        public void setParticipantInn(String participantInn) { this.participantInn = participantInn; }
    }

    public static class Product {
        @JsonProperty("certificate_document")
        private String certificateDocument;

        @JsonProperty("certificate_document_date")
        private String certificateDocumentDate;

        @JsonProperty("certificate_document_number")
        private String certificateDocumentNumber;

        @JsonProperty("owner_inn")
        private String ownerInn;

        @JsonProperty("producer_inn")
        private String producerInn;

        @JsonProperty("production_date")
        private String productionDate;

        @JsonProperty("tnved_code")
        private String tnvedCode;

        @JsonProperty("uit_code")
        private String uitCode;

        @JsonProperty("uitu_code")
        private String uituCode;

        public Product() {}

        public String getCertificateDocument() { return certificateDocument; }
        public void setCertificateDocument(String certificateDocument) { this.certificateDocument = certificateDocument; }

        public String getCertificateDocumentDate() { return certificateDocumentDate; }
        public void setCertificateDocumentDate(String certificateDocumentDate) { this.certificateDocumentDate = certificateDocumentDate; }

        public String getCertificateDocumentNumber() { return certificateDocumentNumber; }
        public void setCertificateDocumentNumber(String certificateDocumentNumber) { this.certificateDocumentNumber = certificateDocumentNumber; }

        public String getOwnerInn() { return ownerInn; }
        public void setOwnerInn(String ownerInn) { this.ownerInn = ownerInn; }

        public String getProducerInn() { return producerInn; }
        public void setProducerInn(String producerInn) { this.producerInn = producerInn; }

        public String getProductionDate() { return productionDate; }
        public void setProductionDate(String productionDate) { this.productionDate = productionDate; }

        public String getTnvedCode() { return tnvedCode; }
        public void setTnvedCode(String tnvedCode) { this.tnvedCode = tnvedCode; }

        public String getUitCode() { return uitCode; }
        public void setUitCode(String uitCode) { this.uitCode = uitCode; }

        public String getUituCode() { return uituCode; }
        public void setUituCode(String uituCode) { this.uituCode = uituCode; }
    }

    public static class DocumentResponse {
        private String value;
        private String code;

        @JsonProperty("error_message")
        private String errorMessage;

        private String description;

        public DocumentResponse() {}

        public DocumentResponse(String value) {
            this.value = value;
        }

        public DocumentResponse(String code, String errorMessage, String description) {
            this.code = code;
            this.errorMessage = errorMessage;
            this.description = description;
        }

        public String getValue() { return value; }
        public String getCode() { return code; }
        public String getErrorMessage() { return errorMessage; }
        public String getDescription() { return description; }

        public boolean isSuccess() {
            return value != null && code == null;
        }

        public boolean isError() {
            return code != null;
        }
    }
}