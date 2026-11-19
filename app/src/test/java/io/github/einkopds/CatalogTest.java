package io.github.einkopds;

import android.app.Application;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE, application = Application.class)
public class CatalogTest {
    private static final String AUTH = "Basic cmVhZGVyOnNlY3JldA==";
    private MockWebServer server;
    private String origin;

    @Before public void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        origin = address(server);
    }

    @After public void stopServer() throws IOException {
        if (server != null) server.shutdown();
    }

    @Test public void atomFollowsAuthenticatedRedirectAndParsesGzippedFeed() throws Exception {
        server.enqueue(redirect("/catalog/feed"));
        byte[] feed = gzip("""
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
                  <title>Test library</title>
                  <link rel="next" href="?page=2"/>
                  <entry xml:base="books/">
                    <title>The First Book</title>
                    <author><name>Example Author</name></author>
                    <author><name>Second Author</name></author>
                    <link rel="http://opds-spec.org/acquisition/open-access"
                          type="application/epub+zip" href="first.epub"/>
                    <link rel="http://opds-spec.org/acquisition"
                          type="application/epub+zip" href="first.epub"/>
                    <link rel="http://opds-spec.org/acquisition"
                          type="application/vnd.adobe.adept+xml" href="license.acsm"/>
                    <link rel="http://opds-spec.org/acquisition"
                          type="application/zip" href="container.zip">
                      <opds:indirectAcquisition type="application/epub+zip"/>
                    </link>
                  </entry>
                </feed>
                """);
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/atom+xml")
                .setHeader("Content-Encoding", "gzip").setBody(new Buffer().write(feed)));

        String catalog = origin + "/start";
        FeedReader.Page page = FeedReader.get(catalog, source(catalog), new Http.Call(), Locale.ENGLISH);
        assertEquals(AUTH, request(server).getHeader("Authorization"));
        RecordedRequest redirected = request(server);
        assertEquals("/catalog/feed", redirected.getPath());
        assertEquals(AUTH, redirected.getHeader("Authorization"));
        assertEquals("Test library", page.title);
        assertEquals(origin + "/catalog/feed?page=2", page.next);
        assertEquals(1, page.entries.size());
        FeedReader.Entry book = page.entries.get(0);
        assertEquals("The First Book", book.title);
        assertEquals("Example Author, Second Author", book.author);
        assertEquals(1, book.downloads.size());
        assertEquals(origin + "/catalog/books/first.epub", book.downloads.get(0).url);
    }

    @Test public void downloadsDoNotForwardBasicAuthToAnotherOrigin() throws Exception {
        try (MockWebServer files = new MockWebServer()) {
            files.start();
            files.enqueue(response("text/plain", "book bytes"));
            server.enqueue(redirect(address(files) + "/book.txt"));

            String link = origin + "/book";
            try (Http.Response response = Http.get(link, source(link), "*/*", false, new Http.Call());
                 var body = response.stream()) {
                assertEquals("book bytes", new String(body.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertEquals(AUTH, request(server).getHeader("Authorization"));
            assertNull(request(files).getHeader("Authorization"));
        }
    }

    @Test public void jsonFeedParsesNavigationGroupedBooksAndSearchTemplate() throws Exception {
        server.enqueue(response("application/opds+json", """
                {
                  "metadata": {"title": "JSON Library"},
                  "links": [
                    {"rel": ["next"], "href": "?page=2"},
                    {"rel": ["search"], "href": "search{?query}", "templated": true}
                  ],
                  "navigation": [
                    {"href": "recent", "title": "Recent books", "type": "application/opds+json"}
                  ],
                  "groups": [{
                    "metadata": {"title": "Group"},
                    "publications": [{
                      "metadata": {"title": {"en": "Book", "zh-Hant": "一本書（繁體）", "zh-Hans": "一本书"},
                                   "author": [{"name": "First Author"}, "Second Author"]},
                      "links": [
                        {"rel": ["http://opds-spec.org/acquisition/open-access"],
                         "href": "../files/book.epub", "type": "application/epub+zip"}
                      ]
                    }]
                  }]
                }
                """));

        String catalog = origin + "/opds/catalog";
        LibraryStore.Source source = source(catalog);
        FeedReader.Page page = FeedReader.get(catalog, source, new Http.Call(), Locale.SIMPLIFIED_CHINESE);
        assertEquals("JSON Library", page.title);
        assertEquals(origin + "/opds/catalog?page=2", page.next);
        assertEquals(2, page.entries.size());
        assertEquals(origin + "/opds/recent", page.entries.get(0).catalog);
        FeedReader.Entry book = page.entries.get(1);
        assertEquals("一本书", book.title);
        assertEquals("First Author、Second Author", book.author);
        assertEquals(origin + "/files/book.epub", book.downloads.get(0).url);
        assertNotNull(page.search);
        assertEquals(origin + "/opds/search?query=two%20words%20%26%20more",
                FeedReader.search(page.search, "two words & more", source, new Http.Call()));
    }

    @Test public void jsonMetadataPrefersRequestedLanguageAndFallsBackToAProvidedValue() throws Exception {
        String body = """
                {
                  "metadata": {"title": {"en": "Library", "ja": "書庫", "zh-Hans": "书库"}},
                  "publications": [{
                    "metadata": {"title": {"en": "Book", "ja": "本", "zh-Hans": "书"},
                                 "author": [{"name": {"en": "Author", "ja": "著者", "zh-Hans": "作者"}}]},
                    "links": [{"href": "book.epub", "title": {"en": "Digital book", "ja": "電子書籍"},
                               "type": "application/epub+zip",
                               "rel": "http://opds-spec.org/acquisition/open-access"}]
                  }]
                }
                """;
        String catalog = origin + "/localized";
        LibraryStore.Source source = source(catalog);
        server.enqueue(response("application/opds+json", body));
        server.enqueue(response("application/opds+json", body));
        server.enqueue(response("application/opds+json", body));

        FeedReader.Page japanese = FeedReader.get(catalog, source, new Http.Call(), Locale.JAPAN);
        assertEquals("書庫", japanese.title);
        FeedReader.Entry book = japanese.entries.get(0);
        assertEquals("本", book.title);
        assertEquals("著者", book.author);
        assertEquals("電子書籍", book.downloads.get(0).title);

        FeedReader.Page english = FeedReader.get(catalog, source, new Http.Call(), Locale.US);
        assertEquals("Library", english.title);
        assertEquals("Book", english.entries.get(0).title);

        FeedReader.Page fallback = FeedReader.get(catalog, source, new Http.Call(), Locale.FRENCH);
        assertEquals("Library", fallback.title);
        assertEquals("Book", fallback.entries.get(0).title);
    }

    @Test public void atomSearchResolvesRelativeOpenSearchTemplate() throws Exception {
        server.enqueue(response("application/opensearchdescription+xml", """
                        <OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
                          <Url type="application/atom+xml" template="results?q={searchTerms}"/>
                        </OpenSearchDescription>
                        """));
        String description = origin + "/opds/search.xml";
        FeedReader.Link search = new FeedReader.Link(description, "application/opensearchdescription+xml",
                "search", "", false);
        assertEquals(origin + "/opds/results?q=%E6%B5%8B%E8%AF%95%20book",
                FeedReader.search(search, "测试 book", source(description), new Http.Call()));
    }

    private static LibraryStore.Source source(String url) {
        return new LibraryStore.Source("Test library", url, "reader", "secret");
    }

    private static String address(MockWebServer target) {
        return "http://localhost:" + target.getPort();
    }

    private static RecordedRequest request(MockWebServer target) throws InterruptedException {
        RecordedRequest request = target.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request);
        return request;
    }

    private static MockResponse response(String type, String body) {
        return new MockResponse().setHeader("Content-Type", type).setBody(body);
    }

    private static MockResponse redirect(String destination) {
        return new MockResponse().setResponseCode(302).setHeader("Location", destination);
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(compressed)) {
            output.write(utf8(text));
        }
        return compressed.toByteArray();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
