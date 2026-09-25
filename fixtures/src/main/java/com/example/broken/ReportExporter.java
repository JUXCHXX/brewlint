package com.example.broken;

import java.io.InputStream;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * FIXTURE. Deliberately broken. This file exists so Brewlint's own CI can prove its rules detect
 * what the README claims. It is never compiled: fixtures/ is not a Maven module.
 */
public class ReportExporter {

    private InputStream cachedTemplate;

    public void exportUnclosed() throws IOException {
        InputStream template = new FileInputStream("template.html");
        byte[] data = template.readAllBytes();
        System.out.println(new String(data));
    }

    public void exportWithVar() throws IOException {
        var source = new FileInputStream("data.csv");
        source.read();
    }

    public void exportCorrectly() throws IOException {
        try (InputStream template = new FileInputStream("template.html")) {
            System.out.println(new String(template.readAllBytes()));
        }
    }

    public void exportWithManualClose() throws IOException {
        InputStream template = new FileInputStream("template.html");
        try {
            System.out.println(new String(template.readAllBytes()));
        } finally {
            template.close();
        }
    }

    public void readPassedIn(InputStream template) throws IOException {
        System.out.println(new String(template.readAllBytes()));
    }
}
