package io.github.einkopds;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BookFiles {
    private static final String BINARY = "application/octet-stream";
    private static final String TEMP_PREFIX = ".opds-";
    private static final Pattern DISPOSITION = Pattern.compile(
            "(?:^|;)\\s*(filename\\*?)\\s*=\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|([^;]*))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTENSION = Pattern.compile("\\.([a-zA-Z0-9]{1,10})$");
    private static final Map<String, String> EXTENSIONS = new HashMap<>();

    static {
        EXTENSIONS.put("application/epub+zip", "epub");
        EXTENSIONS.put("application/pdf", "pdf");
        EXTENSIONS.put("application/x-mobipocket-ebook", "mobi");
        EXTENSIONS.put("application/vnd.amazon.ebook", "azw");
        EXTENSIONS.put("application/x-mobi8-ebook", "azw3");
        EXTENSIONS.put("application/x-fictionbook+xml", "fb2");
        EXTENSIONS.put("application/fb2+zip", "fb2.zip");
        EXTENSIONS.put("application/x-zip-compressed-fb2", "fb2.zip");
        EXTENSIONS.put("text/plain", "txt");
        EXTENSIONS.put("text/html", "html");
        EXTENSIONS.put("application/xhtml+xml", "xhtml");
        EXTENSIONS.put("application/rtf", "rtf");
        EXTENSIONS.put("application/zip", "zip");
        EXTENSIONS.put("application/x-cbz", "cbz");
        EXTENSIONS.put("application/vnd.comicbook+zip", "cbz");
        EXTENSIONS.put("application/x-cbr", "cbr");
        EXTENSIONS.put("application/vnd.comicbook-rar", "cbr");
        EXTENSIONS.put("application/x-dtbook+xml", "xml");
        EXTENSIONS.put("application/x-djvu", "djvu");
        EXTENSIONS.put("image/vnd.djvu", "djvu");
    }

    interface Progress {
        void update(long bytes, int percent);
    }

    private BookFiles() {}

    static String label(FeedReader.Link link, Context context) {
        String extension = EXTENSIONS.get(link.type);
        String linkExtension = extension(pathName(link.url));
        if (knownExtension(linkExtension) && !"zip".equals(linkExtension)) extension = linkExtension;
        if (extension == null && knownExtension(linkExtension)) extension = linkExtension;
        if (extension != null) return extension.toUpperCase(Locale.ROOT);
        return link.title.isEmpty() ? context.getString(R.string.ebook_file) : link.title;
    }

    static String download(Context context, Uri tree, LibraryStore.Source source, FeedReader.Link link,
                           String title, Http.Call call, Progress progress) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        SharedPreferences journal = context.getSharedPreferences("download", Context.MODE_PRIVATE);
        recover(resolver, journal, tree);
        Uri folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri partial = null;
        boolean saved = false;
        try (Http.Response response = Http.get(link.url, source,
                link.type.isEmpty() ? "*/*" : link.type + ", */*;q=0.4", false, call);
             InputStream input = response.stream()) {
            String serverType = Http.mediaType(response.header("Content-Type"));
            String name = fileName(response.header("Content-Disposition"), response.url.toExternalForm(),
                    link, title, serverType);
            if (("text/html".equals(serverType) || "application/xhtml+xml".equals(serverType))
                    && !"text/html".equals(link.type) && !"application/xhtml+xml".equals(link.type)
                    && !knownHtml(extension(pathName(link.url)))) {
                throw new Http.Failure(R.string.error_download_html);
            }
            if ("application/vnd.adobe.adept+xml".equals(serverType)
                    || "application/vnd.readium.lcp.license.v1.0+json".equals(serverType)) {
                throw new Http.Failure(R.string.error_download_drm);
            }

            call.check();
            long length = response.length();
            if (length == 0) throw new Http.Failure(R.string.error_file_empty);
            String temporaryName = TEMP_PREFIX + UUID.randomUUID() + ".part";
            partial = create(resolver, folder, temporaryName);
            DocumentInfo info = info(resolver, partial);
            boolean rename = (info.flags & Document.FLAG_SUPPORTS_RENAME) != 0;
            if (!rename) {
                if (!delete(resolver, partial)) throw new Http.Failure(R.string.error_partial_cleanup);
                partial = create(resolver, folder, name);
                info = info(resolver, partial);
            }
            if (!journal.edit().putString("uri", partial.toString())
                    .putString("name", info.name == null ? (rename ? temporaryName : name) : info.name).commit()) {
                throw new Http.Failure(R.string.error_partial_journal);
            }

            try (OutputStream output = resolver.openOutputStream(partial, "w")) {
                if (output == null) throw new IOException("No document stream");
                copy(input, output, length, call, progress);
            }
            call.check();

            if (rename) {
                Uri completed = rename(resolver, tree, partial, name);
                saved = true;
                name = displayName(resolver, completed, name);
            } else {
                saved = true;
                if (info.name != null) name = info.name;
            }
            journal.edit().clear().commit();
            return name;
        } catch (SecurityException failure) {
            throw new Http.Failure(R.string.error_folder_writable);
        } finally {
            if (!saved && partial != null && delete(resolver, partial)) journal.edit().clear().commit();
        }
    }

    static void copy(InputStream input, OutputStream output, long length,
                     Http.Call call, Progress progress) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long bytes = 0;
        long lastBytes = 0;
        long lastTime = SystemClock.elapsedRealtime();
        long progressStep = length > 0 ? Math.max(1, length / 10) : 2 * 1024 * 1024;
        while (true) {
            call.check();
            int count = input.read(buffer);
            if (count < 0) break;
            output.write(buffer, 0, count);
            bytes += count;
            if (bytes - lastBytes < progressStep) continue;
            long now = SystemClock.elapsedRealtime();
            if (now - lastTime >= 1500) {
                int percent = length > 0 ? (int) Math.min(99, bytes * 100.0 / length) : -1;
                progress.update(bytes, percent);
                lastBytes = bytes;
                lastTime = now;
            }
        }
        if (bytes == 0) throw new Http.Failure(R.string.error_file_empty);
        if (length > 0 && bytes != length) throw new Http.Failure(R.string.error_file_incomplete);
    }

    private static Uri create(ContentResolver resolver, Uri folder, String name) throws IOException {
        Uri document = DocumentsContract.createDocument(resolver, folder, BINARY, name);
        if (document == null) throw new Http.Failure(R.string.error_file_create);
        return document;
    }

    private static Uri rename(ContentResolver resolver, Uri tree, Uri partial, String name) throws IOException {
        Uri completed = tryRename(resolver, partial, name);
        if (completed != null) return completed;

        // Some older providers reject duplicate names instead of adding a number.
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        HashSet<String> existing = new HashSet<>();
        try (Cursor cursor = resolver.query(children, new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String child = cursor.getString(0);
                    if (child != null) existing.add(child.toLowerCase(Locale.ROOT));
                }
            }
        }
        if (existing.contains(name.toLowerCase(Locale.ROOT))) {
            String extension = extension(name);
            String suffix = extension.isEmpty() ? "" : name.substring(name.length() - extension.length() - 1);
            String stem = name.substring(0, name.length() - suffix.length());
            for (int number = 1; number <= 100; number++) {
                String candidate = stem + " (" + number + ")" + suffix;
                if (existing.contains(candidate.toLowerCase(Locale.ROOT))) continue;
                completed = tryRename(resolver, partial, candidate);
                if (completed != null) return completed;
            }
        }
        throw new Http.Failure(R.string.error_file_rename);
    }

    private static Uri tryRename(ContentResolver resolver, Uri partial, String name) throws IOException {
        try {
            return DocumentsContract.renameDocument(resolver, partial, name);
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private static String displayName(ContentResolver resolver, Uri document, String fallback) {
        try {
            String name = info(resolver, document).name;
            return name == null ? fallback : name;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static final class DocumentInfo {
        final String name;
        final int flags;

        DocumentInfo(String name, int flags) {
            this.name = name;
            this.flags = flags;
        }
    }

    private static DocumentInfo info(ContentResolver resolver, Uri document) {
        String[] columns = {Document.COLUMN_DISPLAY_NAME, Document.COLUMN_FLAGS};
        try (Cursor cursor = resolver.query(document, columns, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int name = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME);
                int flags = cursor.getColumnIndex(Document.COLUMN_FLAGS);
                return new DocumentInfo(name < 0 ? null : cursor.getString(name), flags < 0 ? 0 : cursor.getInt(flags));
            }
        }
        return new DocumentInfo(null, 0);
    }

    private static boolean delete(ContentResolver resolver, Uri document) {
        try {
            return DocumentsContract.deleteDocument(resolver, document);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void recover(ContentResolver resolver, SharedPreferences journal, Uri tree) throws IOException {
        String stored = journal.getString("uri", null);
        String name = journal.getString("name", null);
        if (stored == null || name == null) return;
        Uri pending = Uri.parse(stored);
        String current = displayName(resolver, pending, null);
        if (name.equals(current) && !delete(resolver, pending) && tree.getAuthority().equals(pending.getAuthority())
                && DocumentsContract.getTreeDocumentId(tree).equals(DocumentsContract.getTreeDocumentId(pending))) {
            throw new Http.Failure(R.string.error_previous_cleanup);
        }
        journal.edit().clear().commit();
    }

    static String fileName(String disposition, String responseAddress, FeedReader.Link link,
                           String title, String serverType) {
        String name = dispositionName(disposition);
        String responseName = pathName(responseAddress);
        String linkName = pathName(link.url);
        if (name.isEmpty()) {
            if (knownExtension(extension(responseName))) name = responseName;
            else if (knownExtension(extension(linkName))) name = linkName;
            else name = title;
        }

        String nameExtension = extension(name);
        String expected = EXTENSIONS.get(link.type);
        if (expected == null) expected = EXTENSIONS.get(serverType);
        if (expected == null && knownExtension(extension(linkName))) expected = extension(linkName);
        if (expected != null && !knownExtension(nameExtension)) {
            name += "." + expected;
        } else if (nameExtension.isEmpty()) {
            name += ".bin";
        }
        return safeName(name);
    }

    private static boolean knownHtml(String extension) {
        return "html".equals(extension) || "htm".equals(extension) || "xhtml".equals(extension);
    }

    private static boolean knownExtension(String extension) {
        return EXTENSIONS.containsValue(extension) || knownHtml(extension)
                || "azw3".equals(extension) || "prc".equals(extension) || "kepub".equals(extension)
                || "lit".equals(extension) || "doc".equals(extension) || "docx".equals(extension)
                || "odt".equals(extension) || "chm".equals(extension);
    }

    private static String dispositionName(String header) {
        if (header == null) return "";
        Matcher matcher = DISPOSITION.matcher(header);
        String regular = "";
        while (matcher.find()) {
            String key = matcher.group(1);
            String quoted = matcher.group(2);
            String value = quoted == null ? matcher.group(3).trim() : quoted.replaceAll("\\\\(.)", "$1");
            if (key.endsWith("*")) {
                int first = value.indexOf('\'');
                int second = value.indexOf('\'', first + 1);
                if (first > 0 && second > first) {
                    String encoding = value.substring(0, first);
                    if ("UTF-8".equalsIgnoreCase(encoding) || "ISO-8859-1".equalsIgnoreCase(encoding)) {
                        try {
                            return decode(value.substring(second + 1), encoding);
                        } catch (Exception ignored) {
                        }
                    }
                }
            } else {
                regular = value;
            }
        }
        return regular;
    }

    private static String pathName(String address) {
        try {
            String path = new URL(address).getPath();
            return decode(path.substring(path.lastIndexOf('/') + 1), "UTF-8");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String decode(String value, String encoding) throws IOException {
        return URLDecoder.decode(value.replace("+", "%2B"), encoding);
    }

    private static String extension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".fb2.zip")) return "fb2.zip";
        Matcher matcher = EXTENSION.matcher(lower);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String safeName(String fileName) {
        String name = fileName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .trim().replaceFirst("^[. ]+", "").replaceFirst("[. ]+$", "");
        if (name.isEmpty()) return "book.bin";
        String extension = extension(name);
        String suffix = extension.isEmpty() ? "" : name.substring(name.length() - extension.length() - 1);
        int limit = 190 - suffix.length();
        int offset = 0;
        int bytes = 0;
        int end = name.length() - suffix.length();
        while (offset < end) {
            int code = name.codePointAt(offset);
            int size = code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4;
            if (bytes + size > limit) break;
            bytes += size;
            offset += Character.charCount(code);
        }
        return name.substring(0, offset) + suffix;
    }
}
