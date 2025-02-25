package com.example.webtablecollect.service;

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
    private final TableParserService tableParserService;
    private String spreadsheetId;

    public GoogleSheetsService(TableParserService tableParserService) throws GeneralSecurityException, IOException {
        this.sheetsService = getSheetsService();
        this.driveService = getDriveService();
        spreadsheetId = null;
        this.tableParserService = tableParserService;
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

    public String fillData() {
        StringBuilder logWhatIsBad;
        if (this.spreadsheetId == null) {
            return "Спочатку перейдіть <a href=\"/a/cs\">за посиланням /a/cs</a>," +
                    "потім у новоствореній гуглотаблиці заповніть список студентів та перелік id з qbit," +
                    "і лише потім повторно перейдіть за поточним посиланням.";
        } else {
            logWhatIsBad = new StringBuilder();

            Map<Integer, String> qBitIds = null;
            try {
                qBitIds = getQBitIds();
            } catch (IOException ex) {
                return ex.getMessage();
            }
            List<String> studNamesFromSSheet = null;
            try {
                studNamesFromSSheet = getStudNamesFromSSheet();
            } catch (IOException ex) {
                return ex.getMessage();
            }
            for (Map.Entry<Integer, String> entry : qBitIds.entrySet()) {
                try {
                    String fillRes = fillColumn(entry.getKey(), entry.getValue(), studNamesFromSSheet);
                    if (!("OK".equals(fillRes))) {
                        logWhatIsBad.append("<h1>Column ")
                                .append((char) ('A' + entry.getKey()))
                                .append(", id = ")
                                .append(entry.getValue())
                                .append("</h1>\n<br>\n")
                                .append(fillRes);
                    }
                } catch (IOException e) {
                    logWhatIsBad.append("<h1>Column ")
                            .append((char) ('A' + entry.getKey()))
                            .append(" &mdash; completely failed</h1>\n<br>\n");
                    fillColumnWithErrorMessage(entry);
                }
            }
        }
        if (logWhatIsBad.isEmpty())
            return "Done.";
        else
            return logWhatIsBad.toString();
    }

    private void fillColumnWithErrorMessage(Map.Entry<Integer, String> entry) {
        System.out.println("Non-implemented-yet method fillColumnWithErrorMessage was called, entry: " + entry.getKey() + " -> " + entry.getValue());
    }

    private List<String> getStudNamesFromSSheet() throws IOException {
        List<String> res = new ArrayList<>();
        String range = "Аркуш1!A2:A999";
        ValueRange response = sheetsService.spreadsheets().values()
                .get(spreadsheetId, range)
                .execute();
        List<List<Object>> values = response.getValues();
        if (values == null || values.isEmpty()) {
            throw new IOException("Failed getting studNames, values = " + values);
        }
        for (List<Object> row : values) {
            if (row.size() == 1) {
                res.add(row.get(0).toString());
            }
            else res.add("");
        }
        while (!res.isEmpty() && (res.getLast() == null || res.getLast().isEmpty() || res.getLast().isBlank())) {
            res.removeLast();
        }
        if(res.isEmpty()) {
            throw new IOException("Схоже, що Ви не вказали повні імена студентів у стовпчику A");
        }
        res.addFirst("placeHolder");
        return res;
    }

    private Map<Integer, String> getQBitIds() throws IOException {
        Map<Integer, String> res = new HashMap<>();
        String range = "Аркуш1!C1:BZ1";
        ValueRange response = sheetsService.spreadsheets().values()
                    .get(spreadsheetId, range)
                    .execute();
        List<List<Object>> values = response.getValues();
        if (values == null || values.isEmpty()) {
            throw new IOException("Failed getting qBit ids, values = " + values);
        }
        for (List<Object> row : values) {
            for (int i = 0; i < row.size(); i++) {
                String qBitId = row.get(i).toString();
                if (qBitId.matches("\\d+")) {
                    res.put(i+2, row.get(i).toString());
                }
            }
        }
        if(res.isEmpty()) {
            throw new IOException("Схоже, що Ви не вказали перелік ids з qBit у рядку 1");
        }
        return res;
    }

    private String fillColumn(int column, String qBitId, List<String> studNamesFromSSheet) throws IOException {
        StringBuilder logWhatIsBad = new StringBuilder();
        Map<String, Double> nameToScore = tableParserService.fetchResultsAsMap(qBitId);
        Set<String> notCopiedNames = nameToScore.keySet();
        NavigableMap<Integer, Double> changes = new TreeMap<>();
        NavigableSet<Integer> rowsNotFound = new TreeSet<>();
        List<Double> oldMarks = new ArrayList<>();
        oldMarks.add(0, Double.NaN);
        oldMarks.add(1, Double.NaN);

        char columnLetter = (char)('A' + column); // Fails for ranges righter than Z column, but it fails for many other reasons too, so in current version it looks satisfactory
        String range = "Аркуш1!" + columnLetter + "1:" + columnLetter + Math.min(999, studNamesFromSSheet.size());
        ValueRange response = sheetsService.spreadsheets().values()
                .get(spreadsheetId, range)
                .execute();
        List<List<Object>> values = response.getValues();
        if (values == null || values.isEmpty()) {
            throw new IOException("Failed getting old marks, values = " + values);
        }
        for (int i=2; i<studNamesFromSSheet.size(); i++) { // intentionally skip 0 and 1
            if (i >= values.size())
                oldMarks.add(i, 0.0);
            else if (values.get(i).size() == 1) {
                try {
                    oldMarks.add(i, Double.parseDouble(values.get(i).get(0).toString().replace(",", ".")));
                } catch (NumberFormatException ex) {
                    oldMarks.add(i, 0.0);
                }
            } else
                oldMarks.add(i, 0.0);
        }

        for (int i = 2; i < studNamesFromSSheet.size(); i++) {
            String currStudName = studNamesFromSSheet.get(i);
            if (!currStudName.isEmpty()) { // assuming lists MAY contain empty cells (between groups, fired students, etc)
                if (nameToScore.containsKey(currStudName)) {
                    double newScore = nameToScore.get(currStudName);
                    double oldScore = oldMarks.get(i);
                    if(Math.abs(oldScore - newScore) >= 1e-3) {
                        changes.put(i, newScore);
                    }
                    notCopiedNames.remove(currStudName);
                } else {
                    logWhatIsBad.append("\n<br>\n" + currStudName + " at row " + (i+1) + " wasn't found in qBit list");
                    rowsNotFound.add(i);
                }
            }
        }

        if(!notCopiedNames.isEmpty())
            logWhatIsBad.append("\n<br>\nTotally ")
                    .append(notCopiedNames.size())
                    .append(" names from qBit list weren't found in sheet's studNames: ")
                    .append(Arrays.deepToString(notCopiedNames.toArray()));

        if(changes.isEmpty() && rowsNotFound.isEmpty()) {
            logWhatIsBad.append("\n<br>\nNo changes found.");
        }
        else {
            try {
                updateChangedCellsInColumns(spreadsheetId, column, changes, rowsNotFound, logWhatIsBad.toString());
            } catch (IOException e) {
                logWhatIsBad.append("\n<br>\nValues weren't actually updated.\n<br>\nException: " + e.getMessage() + "\n<br>\n");
            }
        }
        if(logWhatIsBad.isEmpty())
            return "OK";
        else
            return logWhatIsBad.toString();
    }

    private void updateChangedCellsInColumns(String spreadsheetId,
                                             int column,
                                             NavigableMap<Integer, Double> changes,
                                             NavigableSet<Integer> rowsNotFound,
                                             String logMessage) throws IOException
    {
        char columnLetter = (char)('A' + column); // Fails for ranges righter than Z column, but it fails for many other reasons too, so in current version it looks satisfactory
        List<ValueRange> ranges = new ArrayList<>();
        if (!(changes.isEmpty())) {
            Integer i = changes.firstKey();
            while (i != null) {
                Integer j = i + 1;
                while (changes.containsKey(j)) j++;
                j--;
                NavigableMap<Integer, Double> sm = changes.subMap(i, true, j, true);
                List<List<Object>> currSegmValues = new ArrayList<>();
                for (NavigableMap.Entry<Integer, Double> entry : sm.entrySet()) {
                    currSegmValues.add(List.of(entry.getValue()));
                }
                ranges.add(new ValueRange()
                        .setRange("Аркуш1!" + columnLetter + (i + 1) + ":" + columnLetter + (j + 1))
                        .setValues(currSegmValues));
                i = changes.higherKey(j);
            }
            // Формуємо запит для оновлення
            BatchUpdateValuesRequest batchBody = new BatchUpdateValuesRequest()
                    .setValueInputOption("RAW")
                    .setData(ranges);
            sheetsService.spreadsheets().values().batchUpdate(spreadsheetId, batchBody).execute();
        }
        List<Request> requests = new ArrayList<>();
        if (!(rowsNotFound.isEmpty())) {
            List<GridRange> rangesNotFound = new ArrayList<>();
            Color lightRed = new Color().setRed(1f).setGreen(0.5f).setBlue(0.5f);
            CellFormat cellFormat = new CellFormat().setBackgroundColor(lightRed);
            CellData cellData = new CellData()
                    .setUserEnteredValue(new ExtendedValue().setStringValue("???")) // Значення комірки "???"
                    .setUserEnteredFormat(cellFormat); // Колір тла
            for (Integer idx : rowsNotFound) {
                rangesNotFound.add(new GridRange().setSheetId(0)
                        .setStartRowIndex(idx).setEndRowIndex(idx + 1)
                        .setStartColumnIndex(column).setEndColumnIndex(column + 1));
            }
            requests.addAll(rangesNotFound.stream()
                    .map(range -> new Request()
                            .setRepeatCell(new RepeatCellRequest()
                                    .setRange(range)
                                    .setCell(cellData)
                                    .setFields("userEnteredValue,userEnteredFormat.backgroundColor") // 🔥 Одним запитом
                            )
                    ).collect(Collectors.toList())
            );

//            BatchUpdateSpreadsheetRequest body = new BatchUpdateSpreadsheetRequest().setRequests(requests);
//            sheetsService.spreadsheets().batchUpdate(spreadsheetId, body).execute();
        }
        if (!(logMessage.isEmpty())) {
            GridRange rangeLogNote = new GridRange().setSheetId(0)
                    .setStartRowIndex(1).setEndRowIndex(2)
                    .setStartColumnIndex(column).setEndColumnIndex(column + 1);
            Color lightYellow = new Color().setRed(1f).setGreen(1f).setBlue(0.75f);
            CellFormat cellFormatLogNote = new CellFormat()
                    .setBackgroundColor(lightYellow)
                    .setTextFormat(new TextFormat().setFontSize(6)) // Шрифт 6pt
                    .setWrapStrategy("WRAP"); // Перенесення тексту
            CellData cellDataLogNote = new CellData()
                    .setUserEnteredValue(new ExtendedValue().setStringValue(logMessage))
                    .setUserEnteredFormat(cellFormatLogNote); // Колір тла
            requests.add(new Request()
                    .setRepeatCell(new RepeatCellRequest()
                            .setRange(rangeLogNote)
                            .setCell(cellDataLogNote)
                            .setFields("userEnteredValue,userEnteredFormat(backgroundColor,textFormat,wrapStrategy)")
                    )
            );

//            BatchUpdateSpreadsheetRequest body = new BatchUpdateSpreadsheetRequest().setRequests(requests);
//            sheetsService.spreadsheets().batchUpdate(spreadsheetId, body).execute();
        }

        if(!(requests.isEmpty())) {
            BatchUpdateSpreadsheetRequest body = new BatchUpdateSpreadsheetRequest().setRequests(requests);
            sheetsService.spreadsheets().batchUpdate(spreadsheetId, body).execute();
        }
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
