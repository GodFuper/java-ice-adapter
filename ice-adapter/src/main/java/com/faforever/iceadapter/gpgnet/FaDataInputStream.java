package com.faforever.iceadapter.gpgnet;

import com.google.common.io.LittleEndianDataInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads data from Forged Alliance (the forgedalliance, not the lobby).
 */
public class FaDataInputStream extends InputStream {

    private static final int MAX_CHUNK_SIZE = 10;
    private static final int FIELD_TYPE_INT = 0;
    private static final int FIELD_TYPE_STRING = 1; // Добавлено: явное определение типа строки

    private final LittleEndianDataInputStream inputStream;
    private final Charset charset = StandardCharsets.UTF_8;

    public FaDataInputStream(InputStream inputStream) {
        // Улучшено: проверка на null
        if (inputStream == null) {
            throw new IllegalArgumentException("Input stream cannot be null");
        }
        this.inputStream = new LittleEndianDataInputStream(new BufferedInputStream(inputStream));
    }

    /**
     * Читает блоки данных из FA. Поддерживает только int и строковые значения.
     * @return список объектов (Integer или String)
     * @throws IOException при ошибках чтения или некорректных данных
     */
    public List<Object> readChunks() throws IOException {
        int numberOfChunks = readInt();

        if (numberOfChunks < 0) {
            throw new IOException("Invalid chunk count: " + numberOfChunks);
        }

        if (numberOfChunks > MAX_CHUNK_SIZE) {
            throw new IOException("Too many chunks: " + numberOfChunks);
        }

        List<Object> chunks = new ArrayList<>(numberOfChunks);

        for (int chunkNumber = 0; chunkNumber < numberOfChunks; chunkNumber++) {
            int fieldType = read();

            switch (fieldType) {
                case FIELD_TYPE_INT:
                    chunks.add(readInt());
                    break;

                case FIELD_TYPE_STRING: // Явная обработка строки
                    String str = readString();
                    // Исправлено: замена экранированных последовательностей
                    str = str.replace("\\t", "\t").replace("\\n", "\n"); // Исправлено: /t → \t
                    chunks.add(str);
                    break;

                default:
                    throw new IOException("Unknown field type: " + fieldType); // Лучше чем молчание
            }
        }

        return chunks;
    }

    public int readInt() throws IOException {
        return inputStream.readInt();
    }

    @Override
    public int read() throws IOException {
        return inputStream.read();
    }

    /**
     * Читает строку с префиксом длины (int).
     */
    public String readString() throws IOException {
        int size = readInt();

        if (size < 0) {
            throw new IOException("Invalid string length: " + size);
        }

        if (size == 0) {
            return ""; // Оптимизация: пустая строка
        }

        byte[] buffer = new byte[size];
        inputStream.readFully(buffer);
        return new String(buffer, charset);
    }

    @Override
    public void close() throws IOException {
        // Добавлен null-check (на всякий случай)
        if (inputStream != null) {
            inputStream.close();
        }
    }
}
