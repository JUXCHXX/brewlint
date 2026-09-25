package com.example.broken;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * FIXTURE. Deliberately broken: JDBC resources that are never closed.
 */
public class InventoryDao {

    public ResultSet findBySku(String sku) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:inventory");
        Statement statement = connection.createStatement();
        return statement.executeQuery("select * from inventory where sku = '" + sku + "'");
    }

    public void exportCsv() throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader("inventory.csv"));
        String line = reader.readLine();
        while (line != null) {
            System.out.println(line);
            line = reader.readLine();
        }
    }

    public void exportCsvCorrectly() throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader("inventory.csv"))) {
            String line = reader.readLine();
            while (line != null) {
                System.out.println(line);
                line = reader.readLine();
            }
        }
    }
}
