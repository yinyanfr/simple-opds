package io.github.einkopds;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SettingsTest {
    @Test public void legacyAppLanguagePersistsAndCanReturnToSystem() {
        Context app = RuntimeEnvironment.getApplication();
        assertSame(AppLanguage.SYSTEM, AppLanguage.selected(app));

        AppLanguage.JAPANESE.select(app);
        assertSame(AppLanguage.JAPANESE, AppLanguage.selected(app));
        assertEquals("設定", AppLanguage.localizedContext(app).getString(R.string.settings));

        AppLanguage.CHINESE.select(app);
        assertEquals("设置", AppLanguage.localizedContext(app).getString(R.string.settings));

        AppLanguage.SYSTEM.select(app);
        assertSame(AppLanguage.SYSTEM, AppLanguage.selected(app));
        assertSame(app, AppLanguage.localizedContext(app));
    }

    @Test public void persistsProxyAndDistinguishesDirectFromSystem() {
        Context app = RuntimeEnvironment.getApplication();
        assertNull(NetworkProxy.load(app).forConnection());

        new NetworkProxy(NetworkProxy.Mode.SOCKS, "::1", 1080).save(app);
        NetworkProxy restored = NetworkProxy.load(app);
        Proxy connection = restored.forConnection();
        assertEquals(Proxy.Type.SOCKS, connection.type());
        InetSocketAddress endpoint = (InetSocketAddress) connection.address();
        assertEquals("::1", endpoint.getHostString());
        assertEquals(1080, endpoint.getPort());
        assertTrue(endpoint.isUnresolved());

        new NetworkProxy(NetworkProxy.Mode.DIRECT, restored.host, restored.port).save(app);
        assertSame(Proxy.NO_PROXY, NetworkProxy.load(app).forConnection());
    }

    @Test public void validatesProxyEndpointsWithoutDns() {
        assertEquals("::1", NetworkProxy.parseHost(" [::1] "));
        assertEquals("xn--bcher-kva.example", NetworkProxy.parseHost("bücher.example"));
        assertEquals("localhost", NetworkProxy.parseHost("localhost"));
        assertEquals(65535, NetworkProxy.parsePort("65535"));

        assertThrows(IllegalArgumentException.class, () -> NetworkProxy.parseHost("http://host"));
        assertThrows(IllegalArgumentException.class, () -> NetworkProxy.parseHost("host:8080"));
        assertThrows(IllegalArgumentException.class, () -> NetworkProxy.parsePort("65536"));
        assertThrows(IllegalArgumentException.class, () -> NetworkProxy.parsePort("0"));
    }

    @Test public void usesConfiguredHttpProxyForRedirects() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/book.epub"));
            server.enqueue(new MockResponse().setBody("book"));
            String catalog = "http://opds.invalid/catalog";
            LibraryStore.Source source = new LibraryStore.Source("Example", catalog, "", "");
            NetworkProxy settings = new NetworkProxy(NetworkProxy.Mode.HTTP, server.getHostName(), server.getPort());
            try (Http.Response response = Http.get(catalog, source, "*/*", false,
                     new Http.Call(settings.forConnection()))) {
                assertEquals("http://opds.invalid/book.epub", response.url.toExternalForm());
            }

            RecordedRequest first = server.takeRequest(5, TimeUnit.SECONDS);
            RecordedRequest second = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(first);
            assertNotNull(second);
            assertTrue(first.getRequestLine().startsWith("GET http://opds.invalid/catalog "));
            assertTrue(second.getRequestLine().startsWith("GET http://opds.invalid/book.epub "));
        }
    }
}
