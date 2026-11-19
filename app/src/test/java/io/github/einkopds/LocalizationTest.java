package io.github.einkopds;

import android.content.Context;
import android.content.res.Configuration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Locale;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LocalizationTest {
    @Test public void resolvesInterfaceStringsForThreeLanguagesAndEnglishFallback() {
        assertEquals("Add catalog", context(Locale.US).getString(R.string.add_catalog));
        assertEquals("新增书库", context(Locale.SIMPLIFIED_CHINESE).getString(R.string.add_catalog));
        assertEquals("書庫を追加", context(Locale.JAPAN).getString(R.string.add_catalog));
        assertEquals("Add catalog", context(Locale.FRANCE).getString(R.string.add_catalog));
    }

    @Test public void pendingDownloadAndHttpMessagesUseTheViewingLanguage() {
        UiMessage progress = new UiMessage(R.string.download_percent, 25);
        assertEquals("Downloading · 25%", progress.text(context(Locale.US)));
        assertEquals("正在下载 · 25%", progress.text(context(Locale.SIMPLIFIED_CHINESE)));
        assertEquals("ダウンロード中 · 25%", progress.text(context(Locale.JAPAN)));

        UiMessage failure = Http.error(new Http.Failure(R.string.error_http_status, 503),
                R.string.error_catalog_load);
        assertEquals("The server returned HTTP 503.", failure.text(context(Locale.US)));
        assertEquals("服务器返回 HTTP 503。", failure.text(context(Locale.SIMPLIFIED_CHINESE)));
        assertEquals("サーバーから HTTP 503 が返されました。", failure.text(context(Locale.JAPAN)));
    }

    private static Context context(Locale locale) {
        Context app = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(app.getResources().getConfiguration());
        configuration.setLocale(locale);
        return app.createConfigurationContext(configuration);
    }
}
