package io.github.einkopds;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE, application = Application.class)
public class BookFilesTest {
    private static final FeedReader.Link EPUB = new FeedReader.Link("https://catalog.example/get/42",
            "application/epub+zip", "http://opds-spec.org/acquisition/open-access", "", false);

    @Test public void prefersEncodedContentDispositionFilename() {
        String name = BookFiles.fileName("attachment; filename=\"fallback.epub\"; "
                        + "filename*=UTF-8''%E6%B5%8B%E8%AF%95%2Bbook.epub",
                "https://files.example/download", EPUB, "Catalog title", "application/octet-stream");
        assertEquals("测试+book.epub", name);
    }

    @Test public void sanitizesFilenameAndPreservesExtensionAtByteLimit() {
        String header = "attachment; filename=\"../" + "书".repeat(100) + "\"";
        String name = BookFiles.fileName(header, "https://files.example/download", EPUB,
                "Catalog title", "application/octet-stream");
        assertFalse(name.contains("/"));
        assertFalse(name.startsWith("."));
        assertTrue(name.endsWith(".epub"));
        assertTrue(name.getBytes(StandardCharsets.UTF_8).length <= 190);
        assertFalse(name.contains("\ufffd"));
    }

    @Test public void streamsFileWithoutChangingBytes() throws Exception {
        byte[] payload = new byte[96 * 1024 + 3];
        new Random(2).nextBytes(payload);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BookFiles.copy(new ByteArrayInputStream(payload), output, payload.length,
                new Http.Call(), (bytes, percent) -> {});
        assertArrayEquals(payload, output.toByteArray());
    }

    @Test public void rejectsTruncatedAndEmptyDownloads() {
        assertThrows(Http.Failure.class, () -> BookFiles.copy(
                new ByteArrayInputStream(new byte[]{1, 2}), new ByteArrayOutputStream(), 3,
                new Http.Call(), (bytes, percent) -> {}));
        assertThrows(Http.Failure.class, () -> BookFiles.copy(
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), -1,
                new Http.Call(), (bytes, percent) -> {}));
    }

    @Test public void cancelledDownloadWritesNothing() {
        Http.Call call = new Http.Call();
        call.cancel();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(InterruptedIOException.class, () -> BookFiles.copy(
                new ByteArrayInputStream(new byte[]{1}), output, 1, call, (bytes, percent) -> {}));
        assertEquals(0, output.size());
    }
}
