package com.zimbra.cs.service;

public class ContentType {
    private final String mimeType;

    // Predefined ContentType constants
    public static final ContentType APPLICATION_PDF = new ContentType("application/pdf");
    public static final ContentType APPLICATION_EXCEL = new ContentType("application/vnd.ms-excel");
    public static final ContentType APPLICATION_EXCEL_XLSX = new ContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    public static final ContentType TEXT_CSV = new ContentType("text/csv");

    // Constructor
    private ContentType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getMimeType() {
        return mimeType;
    }

    // Static factory method to create ContentType
    public static ContentType create(String mimeType) {
        return new ContentType(mimeType);
    }
}
