package com.cofreseguro;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lê exportações de gerenciadores de senha (Chrome/Google, Bitwarden, Firefox) e
 * planilhas genéricas. Sem dependências do Android nem de org.json.
 */
final class CsvImport {
    static final int MAX_ROWS = 5000;

    private static final String[] SITE_COLUMNS = {"name", "site", "servico", "nome"};
    private static final String[] USER_COLUMNS = {"username", "login_username", "usuario", "login", "email", "user"};
    private static final String[] PASSWORD_COLUMNS = {"password", "login_password", "senha", "pass", "pwd"};
    private static final String[] NOTE_COLUMNS = {"note", "notes", "notas", "observacoes"};
    private static final String[] URL_COLUMNS = {"url", "login_uri"};

    private CsvImport() {
    }

    static final class Row {
        final String site;
        final String username;
        final String password;
        final String notes;

        Row(String site, String username, String password, String notes) {
            this.site = site;
            this.username = username;
            this.password = password;
            this.notes = notes;
        }
    }

    static List<Row> parse(String input) {
        if (input == null) {
            throw new IllegalArgumentException("O arquivo está vazio");
        }
        String text = input.startsWith("﻿") ? input.substring(1) : input;
        char delimiter = delimiterOf(text);
        List<List<String>> records = tokenize(text, delimiter);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("O arquivo não tem registros");
        }

        List<String> first = records.get(0);
        int[] columns = mapHeader(first);
        int start = 0;
        boolean hasHeader = columns != null;
        if (!hasHeader) {
            if (first.size() < 3) {
                throw new IllegalArgumentException("Não reconheci as colunas do arquivo");
            }
            columns = new int[]{0, 1, 2, 3, -1};
        } else {
            start = 1;
        }
        int typeColumn = hasHeader ? indexOf(first, "type") : -1;

        List<Row> rows = new ArrayList<>();
        for (int r = start; r < records.size(); r++) {
            List<String> record = records.get(r);
            if (typeColumn >= 0 && !"login".equalsIgnoreCase(cell(record, typeColumn))
                    && cell(record, typeColumn).length() > 0) {
                continue;
            }
            String name = cell(record, columns[0]).trim();
            String url = cell(record, columns[4]).trim();
            String username = cell(record, columns[1]);
            String password = cell(record, columns[2]);
            String notes = cell(record, columns[3]);

            String site = name.length() > 0 ? name : hostOf(url);
            if (site.length() == 0 && password.length() == 0) {
                continue;
            }
            if (site.length() == 0) {
                site = "Sem nome";
            }
            if (rows.size() >= MAX_ROWS) {
                throw new IllegalArgumentException("Arquivo com mais de " + MAX_ROWS + " registros");
            }
            rows.add(new Row(site, username.trim(), password, notes.trim()));
        }
        return rows;
    }

    /**
     * Retorna os índices [site, usuário, senha, notas, url] ou null se o cabeçalho não
     * for reconhecido. Índice -1 quando a coluna não existe.
     */
    private static int[] mapHeader(List<String> header) {
        List<String> names = new ArrayList<>();
        for (String cell : header) {
            names.add(normalize(cell));
        }
        int password = findColumn(names, PASSWORD_COLUMNS);
        if (password < 0) {
            return null;
        }
        int site = findColumn(names, SITE_COLUMNS);
        int url = findColumn(names, URL_COLUMNS);
        if (site < 0 && url < 0) {
            return null;
        }
        int user = findColumn(names, USER_COLUMNS);
        int notes = findColumn(names, NOTE_COLUMNS);
        return new int[]{site, user, password, notes, url};
    }

    private static int findColumn(List<String> names, String[] candidates) {
        for (String candidate : candidates) {
            int index = names.indexOf(candidate);
            if (index >= 0) {
                return index;
            }
        }
        return -1;
    }

    private static int indexOf(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (normalize(header.get(i)).equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static String normalize(String value) {
        String decomposed = Normalizer.normalize(value.trim(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    private static char delimiterOf(String text) {
        int end = 0;
        while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
            end++;
        }
        String firstLine = text.substring(0, end);
        int semicolons = 0;
        int commas = 0;
        for (int i = 0; i < firstLine.length(); i++) {
            if (firstLine.charAt(i) == ';') {
                semicolons++;
            } else if (firstLine.charAt(i) == ',') {
                commas++;
            }
        }
        return semicolons > commas ? ';' : ',';
    }

    /** RFC 4180: aspas, aspas escapadas (""), quebras dentro de aspas, CRLF ou LF. */
    static List<List<String>> tokenize(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < length && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delimiter) {
                record.add(field.toString());
                field.setLength(0);
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
                    i++;
                }
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (inQuotes) {
            throw new IllegalArgumentException("Há aspas sem fechamento no arquivo");
        }
        if (field.length() > 0 || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        List<List<String>> nonBlank = new ArrayList<>();
        for (List<String> item : records) {
            if (!(item.size() == 1 && item.get(0).trim().length() == 0)) {
                nonBlank.add(item);
            }
        }
        return nonBlank;
    }

    private static String cell(List<String> record, int index) {
        if (index < 0 || index >= record.size()) {
            return "";
        }
        return record.get(index);
    }

    /** Host de uma URL, sem esquema, usuário, porta, caminho e "www.". Vazio se não houver. */
    static String hostOf(String url) {
        String value = url.trim();
        if (value.length() == 0) {
            return "";
        }
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int end = value.length();
        for (char stop : new char[]{'/', '?', '#'}) {
            int index = value.indexOf(stop);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        value = value.substring(0, end);
        int at = value.lastIndexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        int port = value.lastIndexOf(':');
        if (port >= 0) {
            value = value.substring(0, port);
        }
        if (value.toLowerCase(Locale.ROOT).startsWith("www.")) {
            value = value.substring(4);
        }
        return value.trim().length() == 0 ? url.trim() : value.trim();
    }
}
