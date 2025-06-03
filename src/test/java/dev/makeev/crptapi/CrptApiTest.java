package dev.makeev.crptapi;

import dev.makeev.crptapi.CrptApi.Document;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrptApiTest {

    private static final int HTTP_CREATED = 201;

    private CrptApi api;
    private HttpClient mockClient;
    private HttpResponse mockResponse;

    @BeforeEach
    void setUp() throws Exception {
        api = new CrptApi(TimeUnit.SECONDS, 1);
        mockClient = mock(HttpClient.class);
        mockResponse = mock(HttpResponse.class);
        injectMockHttpClient(api, mockClient);
        when(mockClient.send(any(), any())).thenReturn(mockResponse);
    }

    @AfterEach
    void tearDown() {
        if (api != null) {
            api.close();
        }
    }

    @Test
    @DisplayName("Создание документа: успешно")
    void testCreateDocumentSuccess() throws Exception {
        mockResponse(HTTP_CREATED, "{\"value\":\"success\"}");

        var response = getDocumentResponse();

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertFalse(response.isError());
        assertEquals("success", response.getValue());

        verify(mockClient, times(1)).send(any(), any());
    }

    @Test
    @DisplayName("Создание документа: ошибка HTTP")
    void testCreateDocumentHttpError() throws Exception {
        mockResponse(400, "Bad Request");

        var response = getDocumentResponse();

        expectedError(response, "Bad Request", "400");
        verify(mockClient, times(1)).send(any(), any());
    }

    @Test
    @DisplayName("Создание документа: ошибка сервера")
    void testCreateDocumentServerError() throws Exception {
        mockResponse(500, "Internal Server Error");

        var response = getDocumentResponse();

        expectedError(response, "Internal Server Error", "500");
        verify(mockClient, times(1)).send(any(), any());
    }

    @Test
    @DisplayName("Создание документа: ошибка JSON")
    void testCreateDocumentJsonError() throws Exception {
        mockResponse(HTTP_CREATED, "invalid json");

        var response = getDocumentResponse();

        expectedError(response, "Неверная структура JSON ответа", "JSON_PARSE_ERROR");
        verify(mockClient, times(1)).send(any(), any());
    }

    @Test
    @DisplayName("Создание документа: обязательные параметры не могут быть null")
    void testCreateDocumentNullParameters() throws ExecutionException, InterruptedException, TimeoutException {
        assertErrorInvalidParams(api.createDocument(null, 1, "signature", "token"));
        assertErrorInvalidParams(api.createDocument(buildValidDocument(), 1, null, "token"));
        assertErrorInvalidParams(api.createDocument(buildValidDocument(), 0, "signature", null));
    }

    @Test
    @Timeout(10)
    @DisplayName("Создание документа: ограничение по количеству запросов")
    void testRateLimitDelaysRequests() throws Exception {
        var limitedApi = new CrptApi(TimeUnit.SECONDS, 1);

        try (limitedApi) {
            injectMockHttpClient(limitedApi, mockClient);
            long startTime = System.currentTimeMillis();

            var future1 = limitedApi.createDocument(buildValidDocument(), 1, "sig1", "token");
            var future2 = limitedApi.createDocument(buildValidDocument(), 1, "sig2", "token");

            future1.get();
            future2.get();

            long duration = System.currentTimeMillis() - startTime;

            assertTrue(duration > 1000,
                    String.format("Ожидалась задержка минимум 1001ms, получено %dms", duration));

            verify(mockClient, times(2)).send(any(), any());
        }
    }

    @Test
    @Timeout(15)
    @DisplayName("Создание документа: ограничение по количеству запросов")
    void testRateLimitMultipleRequests() throws Exception {
        var limitedApi = new CrptApi(TimeUnit.SECONDS, 2);

        try (limitedApi) {
            injectMockHttpClient(limitedApi, mockClient);
            long startTime = System.currentTimeMillis();

            var futures = new CompletableFuture[5];
            for (int i = 0; i < 5; i++) {
                futures[i] = (CompletableFuture<CrptApi.DocumentResponse>) limitedApi.createDocument(
                        buildValidDocument(), 1, "sig" + i, "token");
            }

            for (var future : futures) {
                future.get(3, TimeUnit.SECONDS);
            }

            long duration = System.currentTimeMillis() - startTime;

            assertTrue(duration > 2000,
                    String.format("Ожидалась задержка минимум 2001ms, получено %dms", duration));

            verify(mockClient, times(5)).send(any(), any());
        }
    }

    @Test
    @DisplayName("Создание документа: клиент API закрыт")
    void testApiClosedPreventsNewRequests() throws ExecutionException, InterruptedException, TimeoutException, IOException {
        api.close();
        var response = getDocumentResponse();

        expectedError(response, "Клиент API был закрыт", "CLIENT_CLOSED");
        verify(mockClient, times(0)).send(any(), any());
    }

    @Test
    @DisplayName("Конструктор: валидация")
    void testConstructorValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new CrptApi(TimeUnit.SECONDS, 0));

        assertThrows(IllegalArgumentException.class,
                () -> new CrptApi(TimeUnit.SECONDS, -1));

        assertThrows(IllegalArgumentException.class,
                () -> new CrptApi(null, 1));
    }

    @Test
    @DisplayName("Создание документа: сетевая ошибка")
    void testNetworkException() throws Exception {
        when(mockClient.send(any(), any())).thenThrow(new RuntimeException("Network error"));

        var response = getDocumentResponse();

        expectedError(response, "Сетевая ошибка", "NETWORK_ERROR");
    }

    @Test
    @DisplayName("Создание документа: параллельные запросы")
    void testConcurrentRequests() throws Exception {
        mockResponse(HTTP_CREATED, "{\"value\":\"success\"}");

        var executor = Executors.newFixedThreadPool(5);
        var futures = new CompletableFuture[10];

        try {
            for (int i = 0; i < 10; i++) {
                final int index = i;
                futures[i] = CompletableFuture.supplyAsync(() -> {
                    try {
                        return api.createDocument(
                                        buildValidDocument(), 1, "sig" + index, "token")
                                .get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, executor);
            }

            for (var future : futures) {
                assertNotNull(future.get(15, TimeUnit.SECONDS));
            }

            verify(mockClient, times(10)).send(any(), any());
        } finally {
            executor.shutdown();
        }
    }

    private CrptApi.DocumentResponse getDocumentResponse() throws InterruptedException, ExecutionException, TimeoutException {
        var future = api.createDocument(buildValidDocument(), 1, "signature", "token");
        return future.get(5, TimeUnit.SECONDS);
    }

    private void mockResponse(int httpCreated, String t) {
        when(mockResponse.statusCode()).thenReturn(httpCreated);
        when(mockResponse.body()).thenReturn(t);
    }

    private void assertErrorInvalidParams(Future<CrptApi.DocumentResponse> api) throws InterruptedException, ExecutionException, TimeoutException {
        var response = api.get(5, TimeUnit.SECONDS);
        expectedError(response, "Обязательные параметры не могут быть null", "INVALID_PARAMS");
    }

    private static void expectedError(CrptApi.DocumentResponse response, String errorMassage, String errorCode) {
        assertNotNull(response);
        assertTrue(response.isError());
        assertFalse(response.isSuccess());
        assertEquals(errorMassage, response.getDescription());
        assertEquals(errorCode, response.getCode());
    }

    private Document buildValidDocument() {
        var doc = new Document();
        doc.setRegNumber("REG123");
        doc.setRegDate(LocalDate.now().toString());
        doc.setDocId("DOC123");
        doc.setDocStatus("NEW");
        doc.setDocType("LP_INTRODUCE_GOODS");
        doc.setOwnerInn("1234567890");
        doc.setParticipantInn("0987654321");
        doc.setProducerInn("1111111111");
        doc.setProductionDate(LocalDate.now().toString());
        doc.setProductionType("OWN_PRODUCTION");
        doc.setImportRequest(false);
        doc.setProducts(Collections.emptyList());

        var description = new CrptApi.Description();
        description.setParticipantInn("0987654321");
        doc.setDescription(description);

        return doc;
    }

    private void injectMockHttpClient(CrptApi api, HttpClient client) throws Exception {
        var clientField = CrptApi.class.getDeclaredField("httpClient");
        clientField.setAccessible(true);
        clientField.set(api, client);
    }
}