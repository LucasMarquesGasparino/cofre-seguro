package com.cofreseguro;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;

/** Testes da lógica pura (sem Android). Executado por tests/run.sh. */
public final class PureLogicTest {
    private static int count;

    public static void main(String[] args) throws Exception {
        pbkdf2Vector();
        sealOpenRoundTrip();
        wrongPasswordRejected();
        alteredDataRejected();
        tamperedIterationsRejected();
        sealsDiffer();
        iterationLimitsEnforced();
        generatorLength();
        generatorClasses();
        generatorAvoidsAmbiguous();
        generatorDistinct();
        strengthScores();
        csvChrome();
        csvBitwarden();
        csvFirefox();
        csvGenericPortuguese();
        csvWithoutHeader();
        csvQuotesAndLineBreaks();
        csvBomAndSemicolon();
        csvKeepsPasswordSpaces();
        hostExtraction();
        System.out.println("OK " + count + " testes");
    }

    private static void check(boolean ok, String name) {
        if (!ok) {
            System.out.println("FALHOU: " + name);
            System.exit(1);
        }
        count++;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format("%02x", b & 0xff));
        }
        return out.toString();
    }

    private static void pbkdf2Vector() throws Exception {
        byte[] key = BackupCrypto.deriveKey("password".toCharArray(),
                "salt".getBytes(StandardCharsets.UTF_8), 4096);
        check("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a".equals(hex(key)),
                "vetor PBKDF2-HMAC-SHA256");
    }

    private static void sealOpenRoundTrip() throws Exception {
        char[] password = "ção🔑Senha".toCharArray();
        byte[] plain = "{\"version\":1,\"entries\":[{\"site\":\"Banco\"}]}".getBytes(StandardCharsets.UTF_8);
        BackupCrypto.Sealed sealed = BackupCrypto.seal(password, plain, BackupCrypto.DEFAULT_ITERATIONS);
        byte[] back = BackupCrypto.open(password, sealed.iterations, sealed.salt, sealed.iv, sealed.data);
        check(Arrays.equals(plain, back), "ida e volta seal/open com acento e emoji");
    }

    private static void wrongPasswordRejected() throws Exception {
        BackupCrypto.Sealed sealed = seal("senha correta", "dados".getBytes(StandardCharsets.UTF_8),
                BackupCrypto.MIN_ITERATIONS);
        boolean rejected = false;
        try {
            BackupCrypto.open("senha errada".toCharArray(), sealed.iterations, sealed.salt, sealed.iv, sealed.data);
        } catch (BackupCrypto.WrongPasswordException expected) {
            rejected = true;
        }
        check(rejected, "senha errada gera WrongPasswordException");
    }

    private static void alteredDataRejected() throws Exception {
        char[] password = "senha de teste".toCharArray();
        BackupCrypto.Sealed sealed = BackupCrypto.seal(password, "dados".getBytes(StandardCharsets.UTF_8),
                BackupCrypto.MIN_ITERATIONS);
        byte[] encrypted = Base64.getDecoder().decode(sealed.data);
        encrypted[0] ^= 0x01;
        boolean rejected = false;
        try {
            BackupCrypto.open(password, sealed.iterations, sealed.salt, sealed.iv,
                    Base64.getEncoder().encodeToString(encrypted));
        } catch (BackupCrypto.WrongPasswordException expected) {
            rejected = true;
        }
        check(rejected, "1 byte alterado gera WrongPasswordException");
    }

    private static void tamperedIterationsRejected() throws Exception {
        char[] password = "senha de teste".toCharArray();
        BackupCrypto.Sealed sealed = BackupCrypto.seal(password, "dados".getBytes(StandardCharsets.UTF_8),
                BackupCrypto.MIN_ITERATIONS);
        boolean rejected = false;
        try {
            BackupCrypto.open(password, sealed.iterations + 1, sealed.salt, sealed.iv, sealed.data);
        } catch (BackupCrypto.WrongPasswordException expected) {
            rejected = true;
        }
        check(rejected, "iterações adulteradas quebram a autenticação (AAD)");
    }

    private static void sealsDiffer() throws Exception {
        char[] password = "senha de teste".toCharArray();
        byte[] plain = "mesmo texto".getBytes(StandardCharsets.UTF_8);
        BackupCrypto.Sealed first = BackupCrypto.seal(password, plain, BackupCrypto.MIN_ITERATIONS);
        BackupCrypto.Sealed second = BackupCrypto.seal(password, plain, BackupCrypto.MIN_ITERATIONS);
        check(!first.salt.equals(second.salt), "sal aleatório por backup");
        check(!first.iv.equals(second.iv), "IV aleatório por backup");
    }

    private static void iterationLimitsEnforced() throws Exception {
        boolean sealRejected = false;
        try {
            BackupCrypto.seal("x".toCharArray(), new byte[1], BackupCrypto.MIN_ITERATIONS - 1);
        } catch (BackupCrypto.BackupFormatException expected) {
            sealRejected = true;
        }
        check(sealRejected, "iterações abaixo do mínimo recusadas");

        boolean openRejected = false;
        try {
            BackupCrypto.open("x".toCharArray(), BackupCrypto.MAX_ITERATIONS + 1,
                    "AAAAAAAAAAAAAAAAAAAAAA==", "AAAAAAAAAAAAAAAA", "AAAA");
        } catch (BackupCrypto.BackupFormatException expected) {
            openRejected = true;
        }
        check(openRejected, "iterações acima do máximo recusadas");
    }

    private static BackupCrypto.Sealed seal(String password, byte[] plain, int iterations) throws Exception {
        return BackupCrypto.seal(password.toCharArray(), plain, iterations);
    }

    private static void generatorLength() {
        SecureRandom random = new SecureRandom();
        check(PasswordGenerator.generate(random, 20, true, true, true).length() == 20, "tamanho 20");
        check(PasswordGenerator.generate(random, 1, true, true, true).length() == 8, "mínimo 8");
        check(PasswordGenerator.generate(random, 500, true, true, true).length() == 64, "máximo 64");
    }

    private static void generatorClasses() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 50; i++) {
            String value = PasswordGenerator.generate(random, 16, true, true, true);
            boolean upper = false;
            boolean digit = false;
            boolean symbol = false;
            for (char c : value.toCharArray()) {
                if (c >= 'A' && c <= 'Z') upper = true;
                else if (c >= '2' && c <= '9') digit = true;
                else if ("!@#$%&*-_=+?".indexOf(c) >= 0) symbol = true;
            }
            check(upper && digit && symbol, "cada classe escolhida aparece");
        }
        String onlyLower = PasswordGenerator.generate(random, 20, false, false, false);
        check(onlyLower.matches("[a-z]{20}"), "somente minúsculas quando nada mais é marcado");
    }

    private static void generatorAvoidsAmbiguous() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 200; i++) {
            String value = PasswordGenerator.generate(random, 40, true, true, true);
            check(value.indexOf('l') < 0 && value.indexOf('I') < 0 && value.indexOf('O') < 0
                    && value.indexOf('0') < 0 && value.indexOf('1') < 0, "sem caracteres ambíguos");
        }
    }

    private static void generatorDistinct() {
        SecureRandom random = new SecureRandom();
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(PasswordGenerator.generate(random, 20, true, true, true));
        }
        check(seen.size() == 200, "200 gerações distintas");
    }

    private static void strengthScores() {
        check(PasswordStrength.score("") == 0, "vazia = 0");
        check(PasswordStrength.score("123456") == 0, "comum = 0");
        check(PasswordStrength.score("abcd") <= 1, "sequência <= 1");
        check(PasswordStrength.score(PasswordGenerator.generate(new SecureRandom(), 20, true, true, true)) == 4,
                "senha gerada de 20 = 4");
        check(PasswordStrength.score("Tr0ub4dor&3") >= 2, "Tr0ub4dor&3 >= 2");
        check("Forte".equals(PasswordStrength.label(4)), "rótulo 4");
    }

    private static void csvChrome() {
        List<CsvImport.Row> rows = CsvImport.parse(
                "name,url,username,password,note\n"
                        + "Google,https://accounts.google.com,ana@x.com,abc123,nota\n");
        check(rows.size() == 1, "chrome: uma linha");
        CsvImport.Row row = rows.get(0);
        check("Google".equals(row.site) && "ana@x.com".equals(row.username)
                && "abc123".equals(row.password) && "nota".equals(row.notes), "chrome: campos");
    }

    private static void csvBitwarden() {
        List<CsvImport.Row> rows = CsvImport.parse(
                "folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp\n"
                        + ",0,login,Banco,nota,,0,https://banco.com.br,joao,s3nha,\n"
                        + ",0,note,Anotacao,texto,,0,,,,\n");
        check(rows.size() == 1, "bitwarden: ignora tipo diferente de login");
        check("Banco".equals(rows.get(0).site) && "joao".equals(rows.get(0).username)
                && "s3nha".equals(rows.get(0).password) && "nota".equals(rows.get(0).notes),
                "bitwarden: campos");
    }

    private static void csvFirefox() {
        List<CsvImport.Row> rows = CsvImport.parse(
                "url,username,password,httpRealm,formActionOrigin,guid\n"
                        + "https://www.exemplo.com.br/login,ana,senha1,,,\n");
        check(rows.size() == 1 && "exemplo.com.br".equals(rows.get(0).site), "firefox: host sem www");
    }

    private static void csvGenericPortuguese() {
        List<CsvImport.Row> rows = CsvImport.parse(
                "Serviço,Usuário,Senha,Observações\nGmail,ana@gmail.com,xyz789,pessoal\n");
        check(rows.size() == 1 && "Gmail".equals(rows.get(0).site)
                && "xyz789".equals(rows.get(0).password) && "pessoal".equals(rows.get(0).notes),
                "genérico pt-BR com acentos");
    }

    private static void csvWithoutHeader() {
        List<CsvImport.Row> rows = CsvImport.parse("gmail.com,ana,xyz789\nbanco,joao,s3nha\n");
        check(rows.size() == 2 && "gmail.com".equals(rows.get(0).site)
                && "joao".equals(rows.get(1).username), "sem cabeçalho: posicional");
    }

    private static void csvQuotesAndLineBreaks() {
        List<CsvImport.Row> rows = CsvImport.parse(
                "name,username,password,note\n"
                        + "\"Site, Ltda\",\"ana, a\",\"senha \"\"quote\"\", x\",\"linha1\nlinha2\"\n");
        CsvImport.Row row = rows.get(0);
        check("Site, Ltda".equals(row.site) && "ana, a".equals(row.username)
                && "senha \"quote\", x".equals(row.password) && "linha1\nlinha2".equals(row.notes),
                "aspas, vírgulas, aspas escapadas e quebra de linha");
    }

    private static void csvBomAndSemicolon() {
        List<CsvImport.Row> bom = CsvImport.parse("﻿name,username,password\nA,b,c\n");
        check(bom.size() == 1 && "A".equals(bom.get(0).site), "BOM removido");
        List<CsvImport.Row> semicolon = CsvImport.parse("name;url;username;password\nSite;https://x.com;ana;pw\r\n");
        check(semicolon.size() == 1 && "Site".equals(semicolon.get(0).site)
                && "pw".equals(semicolon.get(0).password), "delimitador ponto e vírgula e CRLF");
    }

    private static void csvKeepsPasswordSpaces() {
        List<CsvImport.Row> rows = CsvImport.parse("name,password\nA, pw \n");
        check(" pw ".equals(rows.get(0).password), "senha preservada com espaços");
    }

    private static void hostExtraction() {
        check("exemplo.com.br".equals(CsvImport.hostOf("https://www.exemplo.com.br/login?x")), "host de URL");
        check("loja.com".equals(CsvImport.hostOf("loja.com:8080")), "porta removida");
        check("app.com".equals(CsvImport.hostOf("android://abc@app.com/")), "host com userinfo");
    }
}
