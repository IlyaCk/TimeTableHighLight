package org.example.timetable.highlight.timetablehighlight;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.security.GeneralSecurityException;

@SpringBootApplication
public class TimeTableHighLightApplication {

    public static void main(String[] args) throws GeneralSecurityException, IOException {
        SpringApplication.run(TimeTableHighLightApplication.class, args);

        GoogleSheetsService googleSheetsService = new GoogleSheetsService();
        System.out.println(googleSheetsService.testSpreadsheetAccessLevel("1QihfBMjHCuV5_5QSFtGvbnMqpUcXW8vR7wvkaNjkMKk"));
        String copyId = googleSheetsService.copySpreadsheet("1QihfBMjHCuV5_5QSFtGvbnMqpUcXW8vR7wvkaNjkMKk");
        googleSheetsService.shareSpreadsheetWithUser(copyId, "ilya.porublyov@gmail.com");
        googleSheetsService.highlightCells(copyId);
    }

}
