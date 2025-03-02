package org.example.timetable.highlight.timetablehighlight;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.Permission;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.*;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class GoogleSheetsService {
    private static final String APPLICATION_NAME = "MySpringBootApp";
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String CREDENTIALS_FILE_PATH = "src/main/resources/credentials.json";

    private final Sheets sheetsService;
    private final Drive driveService;
    private String spreadsheetId;

    public GoogleSheetsService() throws GeneralSecurityException, IOException {
        this.sheetsService = getSheetsService();
        this.driveService = getDriveService();
        spreadsheetId = null;
    }

    public String sweepOld(Integer days) {
        if(days == null) {
            days = 400;
        }

        System.out.println(Instant.now().toString());

        String thresholdToErase = (Instant.now().isBefore(Instant.parse("2025-02-09T23:59:59.238708600Z")) ?
                Instant.now().minus(10, ChronoUnit.HOURS) :
                Instant.now().minus(days, ChronoUnit.DAYS)
        ).toString();

        System.out.println(thresholdToErase);

        FileList result = null;
        try {
            result = driveService.files().list()
                    .setQ("mimeType='application/vnd.google-apps.spreadsheet' and modifiedTime < '" + thresholdToErase + "'")
                    .setFields("files(id, name, modifiedTime)")
                    .execute();
        } catch (IOException e) {
            return "Failed to get old files list.<br>" + e.getMessage();
        }

        List<File> files = result.getFiles();
        List<String> deleted = new ArrayList<>();
        List<String> failedToDelete = new ArrayList<>();
        for (File file : files) {
            System.out.println("Видаляю файл: " + file.getName() + " (останнє звернення: " + file.getModifiedTime() + ")");
            try {
                driveService.files().delete(file.getId()).execute();
                deleted.add(file.getId() + " a.k.a. " + file.getName());
            } catch (IOException e) {
                failedToDelete.add(file.getId() + " a.k.a. " + file.getName());
            }
        }
        return "" + deleted.size() + " sheet(s) deleted, " + failedToDelete.size() + " failed to delete;<br>" +
                "<h1>Deleted</h1><br>" +
                Arrays.deepToString(deleted.toArray()) + "<br>" +
                "<h1>Failed to delete</h1><br>" +
                Arrays.deepToString(failedToDelete.toArray()) + ".";
    }

    public enum SheetAccessLevel {
        NO_ACCESS,        // ❌ Немає доступу
        READ_ONLY,        // 🔹 Тільки читання
        READ_WRITE        // ✅ Читання і редагування
    }

    private Sheets getSheetsService() throws IOException, GeneralSecurityException {
        HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        Credential credential = GoogleCredential.fromStream(new FileInputStream(CREDENTIALS_FILE_PATH))
                .createScoped(Collections.singletonList("https://www.googleapis.com/auth/spreadsheets"));

        return new Sheets.Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    public String createSpreadsheet() throws IOException, GeneralSecurityException {
        Sheets sheetsService = getSheetsService();
        Spreadsheet spreadsheet = new Spreadsheet()
                .setProperties(new SpreadsheetProperties()
                        .setTitle("Зведення даних")
                        .setLocale("uk")
                );

        Spreadsheet result = sheetsService.spreadsheets().create(spreadsheet)
                .setFields("spreadsheetId")
                .execute();
        this.spreadsheetId = result.getSpreadsheetId();

        String range = "Аркуш1!A1:B2";
        List<List<Object>> values = Arrays.asList(
                Arrays.asList(
                        "",
                        "Вставте потрібні id на qbit праворуч (C1, D1, ...)"   // Значення для B1
                ),
                Arrays.asList(
                        "Вставте повні імена студентів нижче (A3, A4, ...)",  // Значення для A2
                        ""
                )
        );

        ValueRange body = new ValueRange().setValues(values);

        sheetsService.spreadsheets().values()
                .update(spreadsheetId, range, body)
                .setValueInputOption("RAW")
                .execute();

        // Форматування комірок (дрібний шрифт + перенесення рядків)
        CellFormat cellFormat = new CellFormat()
                .setTextFormat(new TextFormat().setFontSize(6)) // Шрифт 6pt
                .setWrapStrategy("WRAP"); // Перенесення тексту

        // Оновлення форматування для A1:B2
        RepeatCellRequest formatRequest = new RepeatCellRequest()
                .setRange(new GridRange()
                        .setSheetId(0) // ID першого листа
                        .setStartRowIndex(0).setEndRowIndex(2)
                        .setStartColumnIndex(0).setEndColumnIndex(2))
                .setCell(new CellData().setUserEnteredFormat(cellFormat))
                .setFields("userEnteredFormat(textFormat,wrapStrategy)");

        BatchUpdateSpreadsheetRequest batchRequest = new BatchUpdateSpreadsheetRequest()
                .setRequests(Collections.singletonList(new Request().setRepeatCell(formatRequest)));

        sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchRequest).execute();

        return spreadsheetId;
    }

    private Drive getDriveService() throws IOException, GeneralSecurityException {
        GoogleCredential credential = GoogleCredential.fromStream(new FileInputStream(CREDENTIALS_FILE_PATH))
                .createScoped(Collections.singletonList("https://www.googleapis.com/auth/drive"));

        return new Drive.Builder(
                com.google.api.client.googleapis.javanet.GoogleNetHttpTransport.newTrustedTransport(),
                com.google.api.client.json.jackson2.JacksonFactory.getDefaultInstance(),
                credential
        ).setApplicationName("MySpringBootApp").build();
    }

    public void shareSpreadsheetWithUser(String spreadsheetId, String userEmail) throws IOException, GeneralSecurityException {
        Permission permission = new Permission()
                .setType("user") // Додаємо конкретного користувача
                .setRole("writer") // "writer" = редагування
                .setEmailAddress(userEmail);

        driveService.permissions().create(spreadsheetId, permission)
                .setSendNotificationEmail(true) // Надішле email користувачу
                .execute();
    }


    public SheetAccessLevel testSpreadsheetAccessLevel(String id) {
        try {
            sheetsService.spreadsheets().get(id).execute();
            this.spreadsheetId = id;
            try {
                LocalDateTime now = LocalDateTime.now();
                sheetsService.spreadsheets().values()
                        .update(id, "B2", new ValueRange().setValues(List.of(List.of(now.toString()))))
                        .setValueInputOption("RAW")
                        .execute();
                return SheetAccessLevel.READ_WRITE;
            } catch (GoogleJsonResponseException ex) {
                return SheetAccessLevel.READ_ONLY;
            }
        } catch (IOException ex) {
            return SheetAccessLevel.NO_ACCESS;
        }
    }

    public String copySpreadsheet(String sourceSpreadsheetId) throws IOException {
        // Перевіряємо, чи існує оригінальна таблиця (доступ хоча б для читання)
        Spreadsheet originalSheet = sheetsService.spreadsheets().get(sourceSpreadsheetId).execute();
        System.out.println("Оригінальна таблиця знайдена: " + originalSheet.getProperties().getTitle());

        // Використовуємо Drive API для створення копії
        File copyMetadata = new File();
        copyMetadata.setName(originalSheet.getProperties().getTitle() + " - Copy");

        File copiedFile = driveService.files().copy(sourceSpreadsheetId, copyMetadata).execute();
        String newSpreadsheetId = copiedFile.getId();
        System.out.println("Копія створена: " + newSpreadsheetId);

        return newSpreadsheetId;
    }

    public void highlightCells(String spreadsheetId) throws IOException {
        // Отримуємо інформацію про таблицю
        Spreadsheet spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute();
        List<Sheet> sheets = spreadsheet.getSheets();
        List<Request> requests = new ArrayList<>();

        for (Sheet sheet : sheets) {
            String sheetName = sheet.getProperties().getTitle();
            int sheetId = sheet.getProperties().getSheetId();

            // Отримуємо дані аркуша
            String range = sheetName + "!A1:AZ500"; // Налаштуй діапазон за потреби
            ValueRange valueRange = sheetsService.spreadsheets().values()
                    .get(spreadsheetId, range)
                    .execute();
            List<List<Object>> values = valueRange.getValues();

            if (values == null) continue;

            // Проходимо всі клітинки, шукаємо потрібне слово
            for (int row = 0; row < values.size(); row++) {
                List<Object> rowValues = values.get(row);
                for (int col = 0; col < rowValues.size(); col++) {
                    Object cellValue = rowValues.get(col);
                    if (cellValue != null) {
                        String cellValueString = cellValue.toString();
                        System.out.println("row = " + row + ", col = " + col + ", cellValue = " + cellValueString);
                        if(cellValueString.contains("Порубл") || cellValueString.contains("Гребен")) {
                            // Додаємо запит на зміну кольору тла комірки
                            requests.add(new Request()
                                    .setRepeatCell(new RepeatCellRequest()
                                            .setRange(new GridRange()
                                                    .setSheetId(sheetId)
                                                    .setStartRowIndex(row)
                                                    .setEndRowIndex(row + 1)
                                                    .setStartColumnIndex(col)
                                                    .setEndColumnIndex(col + 1))
                                            .setCell(new CellData()
                                                    .setUserEnteredFormat(new CellFormat()
                                                            .setBackgroundColor(new Color()
                                                                    .setRed(cellValueString.contains("Порубл") ? 1f : 0f)  // Жовтий/зелений колір
                                                                    .setGreen(1f)
                                                                    .setBlue(0f))))
                                            .setFields("userEnteredFormat.backgroundColor")));
                        }
                    }
                }
            }
        }

        // Вносимо зміни, якщо є комірки для оновлення
        if (!requests.isEmpty()) {
            BatchUpdateSpreadsheetRequest batchUpdateRequest = new BatchUpdateSpreadsheetRequest()
                    .setRequests(requests);
            sheetsService.spreadsheets().batchUpdate(spreadsheetId, batchUpdateRequest).execute();
            System.out.println("Виділено кольором " + requests.size() + " комірок.");
        } else {
            System.out.println("Жодної комірки з потрібним текстом не знайдено.");
        }
    }

    public void deleteOldSheets() throws IOException {
        FileList result = driveService.files().list()
                .setQ("mimeType='application/vnd.google-apps.spreadsheet' and name contains 'MyApp_'")
                .setFields("files(id, name, createdTime)")
                .execute();

        List<File> files = result.getFiles();
        for (File file : files) {
            System.out.println("Видаляю файл: " + file.getName() + " (" + file.getId() + ")");
            driveService.files().delete(file.getId()).execute();
        }
    }

}
