import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

/** Removes MethodParameters metadata that the bundled D8 cannot read reliably. */
public final class StripMethodParameters {
    private StripMethodParameters() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Classes directory missing");
        visit(new File(args[0]));
    }

    private static void visit(File directory) throws Exception {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) visit(file);
            else if (file.getName().endsWith(".class")) transformFile(file);
        }
    }

    private static void transformFile(File file) throws Exception {
        byte[] input = read(file);
        byte[] output = transform(input);
        File temporary = new File(file.getPath() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            stream.write(output);
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] read(File file) throws IOException {
        try (FileInputStream stream = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static byte[] transform(byte[] input) throws IOException {
        Reader reader = new Reader(input);
        Writer writer = new Writer();
        writer.bytes(reader.bytes(8));
        int constantPoolCount = reader.u2();
        writer.u2(constantPoolCount);
        String[] utf8 = new String[constantPoolCount];
        for (int index = 1; index < constantPoolCount; index++) {
            int tag = reader.u1();
            writer.u1(tag);
            switch (tag) {
                case 1:
                    int length = reader.u2();
                    writer.u2(length);
                    byte[] text = reader.bytes(length);
                    writer.bytes(text);
                    utf8[index] = new String(text, StandardCharsets.UTF_8);
                    break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
                    writer.bytes(reader.bytes(4)); break;
                case 5: case 6:
                    writer.bytes(reader.bytes(8)); index++; break;
                case 7: case 8: case 16: case 19: case 20:
                    writer.bytes(reader.bytes(2)); break;
                case 15:
                    writer.bytes(reader.bytes(3)); break;
                default: throw new IOException("Unknown constant pool tag: " + tag);
            }
        }
        writer.bytes(reader.bytes(6));
        copyU2Array(reader, writer);
        copyMembers(reader, writer, utf8, false);
        copyMembers(reader, writer, utf8, true);
        copyAttributes(reader, writer, utf8, false);
        return writer.toByteArray();
    }

    private static void copyU2Array(Reader reader, Writer writer) throws IOException {
        int count = reader.u2();
        writer.u2(count);
        for (int i = 0; i < count; i++) writer.bytes(reader.bytes(2));
    }

    private static void copyMembers(Reader reader, Writer writer, String[] utf8,
                                    boolean strip) throws IOException {
        int count = reader.u2();
        writer.u2(count);
        for (int i = 0; i < count; i++) {
            writer.bytes(reader.bytes(6));
            copyAttributes(reader, writer, utf8, strip);
        }
    }

    private static void copyAttributes(Reader reader, Writer writer, String[] utf8,
                                       boolean strip) throws IOException {
        int count = reader.u2();
        ArrayList<Attribute> attributes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int nameIndex = reader.u2();
            long length = reader.u4();
            if (length > Integer.MAX_VALUE) throw new IOException("Attribute too large");
            byte[] info = reader.bytes((int) length);
            if (!(strip && "MethodParameters".equals(utf8[nameIndex]))) {
                attributes.add(new Attribute(nameIndex, info));
            }
        }
        writer.u2(attributes.size());
        for (Attribute attribute : attributes) {
            writer.u2(attribute.nameIndex);
            writer.u4(attribute.info.length);
            writer.bytes(attribute.info);
        }
    }

    private static final class Attribute {
        final int nameIndex; final byte[] info;
        Attribute(int nameIndex, byte[] info) { this.nameIndex = nameIndex; this.info = info; }
    }

    private static final class Reader {
        final byte[] bytes; int position;
        Reader(byte[] bytes) { this.bytes = bytes; }
        int u1() throws IOException { ensure(1); return bytes[position++] & 0xff; }
        int u2() throws IOException { return (u1() << 8) | u1(); }
        long u4() throws IOException { return ((long) u1() << 24) | ((long) u1() << 16) | ((long) u1() << 8) | u1(); }
        byte[] bytes(int count) throws IOException {
            ensure(count); byte[] result = new byte[count];
            System.arraycopy(bytes, position, result, 0, count); position += count; return result;
        }
        private void ensure(int count) throws IOException {
            if (count < 0 || position > bytes.length - count) throw new IOException("Truncated class");
        }
    }

    private static final class Writer {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        void u1(int value) { output.write(value & 0xff); }
        void u2(int value) { output.write((value >>> 8) & 0xff); output.write(value & 0xff); }
        void u4(long value) { output.write((int) (value >>> 24) & 0xff); output.write((int) (value >>> 16) & 0xff); output.write((int) (value >>> 8) & 0xff); output.write((int) value & 0xff); }
        void bytes(byte[] bytes) { output.write(bytes, 0, bytes.length); }
        byte[] toByteArray() { return output.toByteArray(); }
    }
}
