# CrptApi - Клиент API Честного знака

Java-библиотека для работы с API Честного знака, обеспечивающая создание документов для ввода в оборот товаров, произведенных в РФ. Библиотека поддерживает ограничение количества запросов (rate limiting) и многопоточное использование.

## Возможности

- Thread-safe реализация для безопасного использования в многопоточной среде
- Настраиваемое ограничение количества запросов к API
- Асинхронное выполнение запросов с использованием `CompletableFuture`
- Корректная обработка ошибок без выбрасывания исключений
- Автоматическое управление ресурсами через интерфейс `AutoCloseable`

## Требования

- Java 11 или выше
- Jackson для сериализации/десериализации JSON

## Использование

### Создание экземпляра API клиента

```java
// Создание клиента с ограничением 10 запросов в минуту
CrptApi api = new CrptApi(TimeUnit.MINUTES, 10);

// Не забудьте закрыть клиент после использования
try (CrptApi api = new CrptApi(TimeUnit.SECONDS, 5)) {
    // Использование API
}
```

### Создание документа

```java
// Создание документа
CrptApi.Document document = new CrptApi.Document();
document.setDocId("document123");
document.setDocStatus("draft");
document.setOwnerInn("1234567890");
// ... заполнение других полей документа

// Отправка документа
Future<CrptApi.DocumentResponse> future = api.createDocument(
    document,
    1, // Код группы товаров (1 - одежда, 2 - обувь и т.д.)
    "signature123", // Подпись документа
    "token123" // Авторизационный токен
);

// Получение результата
CrptApi.DocumentResponse response = future.get(10, TimeUnit.SECONDS);

// Проверка результата
if (response.isSuccess()) {
    System.out.println("Документ успешно создан: " + response.getValue());
} else {
    System.out.println("Ошибка: " + response.getErrorMessage());
}
```

### Коды групп товаров

Библиотека поддерживает следующие коды групп товаров:

| Код | Группа товаров |
|-----|---------------|
| 1   | clothes (одежда) |
| 2   | shoes (обувь) |
| 3   | tobacco (табачная продукция) |
| 4   | perfumery (парфюмерия) |
| 5   | tires (шины) |
| 6   | electronics (электроника) |
| 7   | pharma (лекарства) |
| 8   | milk (молочная продукция) |
| 9   | bicycle (велосипеды) |
| 10  | wheelchairs (кресла-коляски) |

## Обработка ошибок

Библиотека не выбрасывает исключения при ошибках API, вместо этого возвращает объект `DocumentResponse` с информацией об ошибке:

```java
CrptApi.DocumentResponse response = future.get();

if (response.isError()) {
    System.out.println("Код ошибки: " + response.getCode());
    System.out.println("Сообщение: " + response.getErrorMessage());
    System.out.println("Описание: " + response.getDescription());
}
```

## Многопоточное использование

Библиотека безопасна для использования в многопоточной среде. Ограничение количества запросов применяется ко всем потокам, использующим один экземпляр `CrptApi`.

## Лицензия

MIT