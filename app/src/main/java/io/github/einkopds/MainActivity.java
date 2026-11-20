package io.github.einkopds;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements DownloadService.Observer {
    private static final int PICK_DIRECTORY = 1;
    private static final int DIRECTORY_ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final class Screen {
        final String address;
        final String title;
        final FeedReader.Link search;
        final String query;
        int first;
        int offset;

        Screen(String address, String title) {
            this(address, title, null, null);
        }

        Screen(String address, String title, FeedReader.Link search, String query) {
            this.address = address;
            this.title = title;
            this.search = search;
            this.query = query;
        }

        Bundle save() {
            Bundle state = new Bundle();
            state.putString("address", address);
            state.putString("title", title);
            if (search != null) state.putString("search_type", search.type);
            state.putString("query", query);
            state.putInt("first", first);
            state.putInt("offset", offset);
            return state;
        }

        static Screen restore(Bundle state) {
            String address = state.getString("address");
            String query = state.getString("query");
            FeedReader.Link search = query == null ? null :
                    new FeedReader.Link(address, state.getString("search_type"), "search", "", false);
            Screen screen = new Screen(address, state.getString("title"), search, query);
            screen.first = state.getInt("first");
            screen.offset = state.getInt("offset");
            return screen;
        }
    }

    private static final class PendingBook {
        final LibraryStore.Source source;
        final FeedReader.Link link;
        final String title;

        PendingBook(LibraryStore.Source source, FeedReader.Link link, String title) {
            this.source = source;
            this.link = link;
            this.title = title;
        }
    }

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ArrayList<Screen> screens = new ArrayList<>();
    private final LruCache<String, FeedReader.Page> cache = new LruCache<String, FeedReader.Page>(384 * 1024) {
        @Override protected int sizeOf(String key, FeedReader.Page page) {
            return page.cacheBytes();
        }
    };

    private LibraryStore library;
    private LibraryStore.Source source;
    private FeedReader.Page page;
    private FeedReader.Entry book;
    private boolean managing;
    private boolean settings;
    private NetworkProxy proxy;
    private Http.Call request;
    private UiMessage loadError;
    private PendingBook pending;

    private TextView title;
    private ImageButton back;
    private ImageButton manage;
    private ImageButton settingsButton;
    private ListView list;
    private TextView empty;
    private LinearLayout footerPanel;
    private LinearLayout actions;
    private LinearLayout statusPanel;
    private TextView status;
    private ImageButton statusAction;
    private String notice = "";
    private boolean statusRunning;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.localizedContext(base));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AppLanguage.migrateLegacyPreference(this);
        configureWindow();
        library = new LibraryStore(this);
        proxy = NetworkProxy.load(this);
        buildUi();
        if (state != null) restore(state);
        if (screen() == null) render();
        else load(false);
    }

    private void configureWindow() {
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().getDecorView().setForceDarkAllowed(false);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        DownloadService.observe(this);
    }

    @Override protected void onStop() {
        DownloadService.unobserve(this);
        super.onStop();
    }

    @Override protected void onDestroy() {
        cancelRequest();
        network.shutdown();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        rememberPosition();
        if (source != null) {
            state.putString("source", source.id);
            ArrayList<Bundle> saved = new ArrayList<>();
            for (int i = Math.max(0, screens.size() - 24); i < screens.size(); i++) {
                saved.add(screens.get(i).save());
            }
            state.putParcelableArrayList("screens", saved);
        }
        state.putBoolean("managing", managing);
        state.putBoolean("settings", settings);
        if (pending != null) {
            Bundle download = new Bundle();
            download.putString("source", pending.source.id);
            download.putString("url", pending.link.url);
            download.putString("type", pending.link.type);
            download.putString("rel", pending.link.rel);
            download.putString("title", pending.title);
            state.putBundle("download", download);
        }
    }

    private void restore(Bundle state) {
        source = library.find(state.getString("source"));
        if (source != null) {
            ArrayList<Bundle> saved = state.getParcelableArrayList("screens");
            if (saved != null) for (Bundle entry : saved) screens.add(Screen.restore(entry));
        }
        settings = source == null && state.getBoolean("settings");
        managing = source == null && !settings && state.getBoolean("managing");
        Bundle download = state.getBundle("download");
        if (download != null) {
            LibraryStore.Source owner = library.find(download.getString("source"));
            if (owner != null) {
                FeedReader.Link link = new FeedReader.Link(download.getString("url"), download.getString("type"),
                        download.getString("rel"), "", false);
                pending = new PendingBook(owner, link, download.getString("title"));
            }
        }
    }

    private void buildUi() {
        LinearLayout root = column();
        root.setBackgroundColor(Color.WHITE);

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(6), dp(12), dp(6));
        back = button(R.drawable.ic_back, R.string.back, this::onBackPressed);
        LinearLayout.LayoutParams backPosition = new LinearLayout.LayoutParams(WRAP, WRAP);
        backPosition.rightMargin = dp(10);
        header.addView(back, backPosition);
        title = text("", 23);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        manage = button(R.drawable.ic_manage, R.string.manage, () -> {
            managing = true;
            render();
        });
        LinearLayout.LayoutParams managePosition = new LinearLayout.LayoutParams(WRAP, WRAP);
        managePosition.leftMargin = dp(10);
        header.addView(manage, managePosition);
        settingsButton = button(R.drawable.ic_settings, R.string.settings, () -> {
            settings = true;
            render();
        });
        LinearLayout.LayoutParams settingsPosition = new LinearLayout.LayoutParams(WRAP, WRAP);
        settingsPosition.leftMargin = dp(8);
        header.addView(settingsButton, settingsPosition);
        header.setMinimumHeight(dp(60));
        root.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));
        rule(root);

        FrameLayout body = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(new ColorDrawable(Color.BLACK));
        list.setDividerHeight(dp(1));
        list.setCacheColorHint(Color.WHITE);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setVerticalScrollBarEnabled(false);
        list.setDrawSelectorOnTop(true);
        list.setSelector(outlineStates(false));
        list.setOnItemClickListener((parent, view, position, id) -> select(position));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (screen() != null || managing || settings) return false;
            editSource(library.sources.get(position));
            return true;
        });
        body.addView(list, new FrameLayout.LayoutParams(MATCH, MATCH));
        empty = text("", 17);
        empty.setPadding(dp(16), dp(18), dp(16), dp(12));
        body.addView(empty, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP));
        root.addView(body, new LinearLayout.LayoutParams(MATCH, 0, 1));

        statusPanel = column();
        rule(statusPanel);
        LinearLayout statusRow = row();
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(12), dp(4), dp(12), dp(4));
        status = text("", 15);
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, WRAP, 1));
        statusAction = button(R.drawable.ic_close, R.string.close, this::statusAction);
        LinearLayout.LayoutParams statusPosition = new LinearLayout.LayoutParams(WRAP, WRAP);
        statusPosition.leftMargin = dp(8);
        statusRow.addView(statusAction, statusPosition);
        statusPanel.addView(statusRow, new LinearLayout.LayoutParams(MATCH, WRAP));
        statusPanel.setVisibility(View.GONE);
        root.addView(statusPanel, new LinearLayout.LayoutParams(MATCH, WRAP));

        footerPanel = column();
        rule(footerPanel);
        actions = row();
        actions.setPadding(dp(12), dp(6), dp(12), dp(6));
        footerPanel.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));
        root.addView(footerPanel, new LinearLayout.LayoutParams(MATCH, WRAP));
        setContentView(root);
    }

    private Screen screen() {
        return screens.isEmpty() ? null : screens.get(screens.size() - 1);
    }

    private void render() {
        Screen screen = screen();
        boolean home = screen == null && !managing && !settings;
        back.setVisibility(home ? View.GONE : View.VISIBLE);
        manage.setVisibility(home && !library.sources.isEmpty() ? View.VISIBLE : View.GONE);
        settingsButton.setVisibility(home ? View.VISIBLE : View.GONE);
        actions.removeAllViews();
        String information = null;
        if (settings) {
            title.setText(R.string.settings);
        } else if (screen == null) {
            title.setText(managing ? R.string.manage_catalogs : R.string.app_name);
            if (library.sources.isEmpty()) information = getString(R.string.catalog_intro);
            action(R.drawable.ic_add, R.string.add_catalog, () -> editSource(null));
            if (!managing) action(R.drawable.ic_folder, R.string.download_folder, this::downloadFolder);
        } else if (book != null) {
            title.setText(entryTitle(book));
            action(R.drawable.ic_folder, R.string.download_folder, this::downloadFolder);
        } else {
            title.setText(catalogTitle(screen));
            if (request != null) {
                information = getString(R.string.catalog_loading);
            } else if (loadError != null) {
                information = loadError.text(this);
                action(R.drawable.ic_refresh, R.string.retry, () -> load(true));
            } else if (page != null) {
                if (page.entries.isEmpty()) information = getString(R.string.catalog_empty);
                if (page.search != null) action(R.drawable.ic_search, R.string.search, () -> search(page.search));
                if (page.next != null && !page.next.equals(screen.address)) {
                    action(R.drawable.ic_next, R.string.next_page, () -> enter(new Screen(page.next, page.title.isEmpty() ? screen.title : page.title)));
                }
                action(R.drawable.ic_refresh, R.string.refresh, () -> {
                    rememberPosition();
                    load(true);
                });
            }
        }

        boolean hasRows = settings || (screen == null ? !library.sources.isEmpty()
                : book != null || page != null && !page.entries.isEmpty());
        list.setVisibility(hasRows ? View.VISIBLE : View.GONE);
        list.setAdapter(hasRows ? new Rows() : null);
        empty.setText(information == null ? "" : information);
        empty.setVisibility(information == null ? View.GONE : View.VISIBLE);
        footerPanel.setVisibility(actions.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        if (hasRows && screen != null && book == null) {
            list.setSelectionFromTop(screen.first, screen.offset);
        }
    }

    private String catalogTitle(Screen screen) {
        if (page != null && !page.title.isEmpty()) return page.title;
        if (screen.query != null) return getString(R.string.search_for, screen.query);
        return TextUtils.isEmpty(screen.title) ? getString(R.string.catalog_entry) : screen.title;
    }

    private String entryTitle(FeedReader.Entry entry) {
        if (entry.groupMore) {
            return entry.title.isEmpty() ? getString(R.string.catalog_more)
                    : getString(R.string.catalog_more_named, entry.title);
        }
        return entry.title.isEmpty() ? getString(R.string.catalog_untitled) : entry.title;
    }

    private void select(int position) {
        if (settings) {
            if (position == 0) editProxy();
            else chooseLanguage();
        } else if (screen() == null) {
            LibraryStore.Source item = library.sources.get(position);
            if (managing) editSource(item);
            else open(item);
        } else if (book != null) {
            requestDownload(book.downloads.get(position), entryTitle(book));
        } else if (page != null) {
            FeedReader.Entry entry = page.entries.get(position);
            if (!entry.downloads.isEmpty()) {
                rememberPosition();
                book = entry;
                render();
            } else if (entry.catalog != null && !entry.catalog.equals(screen().address)) {
                enter(new Screen(entry.catalog, entry.title));
            } else {
                notice(getString(R.string.error_no_download));
            }
        }
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() {
            if (settings) return 2;
            if (screen() == null) return library.sources.size();
            if (book != null) return book.downloads.size();
            return page == null ? 0 : page.entries.size();
        }

        @Override public Object getItem(int position) {
            if (settings) return position == 0 ? proxy : AppLanguage.selected(MainActivity.this);
            if (screen() == null) return library.sources.get(position);
            if (book != null) return book.downloads.get(position);
            return page.entries.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            ItemView item = convert == null ? new ItemView() : (ItemView) convert;
            Object row = getItem(position);
            if (row instanceof NetworkProxy) {
                item.set(getString(R.string.network_proxy), proxy.summary(MainActivity.this), R.drawable.ic_next, null);
            } else if (row instanceof AppLanguage) {
                item.set(getString(R.string.language), getString(((AppLanguage) row).label), R.drawable.ic_next, null);
            } else if (row instanceof LibraryStore.Source) {
                LibraryStore.Source source = (LibraryStore.Source) row;
                item.set(source.name, source.address);
            } else if (row instanceof FeedReader.Link) {
                FeedReader.Link link = (FeedReader.Link) row;
                String format = BookFiles.label(link, MainActivity.this);
                String label = link.isSample() ? getString(R.string.sample_format, format) : format;
                String detail = format.equalsIgnoreCase(link.title) ? "" : link.title;
                String description = getString(link.isSample() ? R.string.download_sample : R.string.download_format, format);
                item.set(label, detail, R.drawable.ic_download,
                        detail.isEmpty() ? description : description + " · " + detail);
            } else {
                FeedReader.Entry entry = (FeedReader.Entry) row;
                String detail = entry.author;
                if (!entry.downloads.isEmpty()) {
                    String formats = BookFiles.label(entry.downloads.get(0), MainActivity.this);
                    if (entry.downloads.size() > 1) {
                        String second = BookFiles.label(entry.downloads.get(1), MainActivity.this);
                        if (!formats.equals(second)) formats += " / " + second;
                        if (entry.downloads.size() > 2) formats = getString(R.string.formats_more, formats);
                    }
                    detail = detail.isEmpty() ? formats : detail + " · " + formats;
                } else if (entry.catalog != null) {
                    detail = detail.isEmpty() ? getString(R.string.catalog_entry) : detail;
                }
                item.set(entryTitle(entry), detail);
            }
            return item;
        }
    }

    private final class ItemView extends LinearLayout {
        final TextView primary;
        final TextView secondary;

        ItemView() {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            setLayoutParams(new AbsListView.LayoutParams(MATCH, WRAP));
            setMinimumHeight(dp(60));
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(16), dp(9), dp(16), dp(9));
            primary = text("", 18);
            primary.setCompoundDrawablePadding(dp(12));
            primary.setMaxLines(2);
            primary.setEllipsize(TextUtils.TruncateAt.END);
            addView(primary, new LayoutParams(MATCH, WRAP));
            secondary = text("", 14);
            secondary.setSingleLine(true);
            secondary.setEllipsize(TextUtils.TruncateAt.END);
            LayoutParams subline = new LayoutParams(MATCH, WRAP);
            subline.topMargin = dp(3);
            addView(secondary, subline);
        }

        void set(String label, String detail) {
            set(label, detail, 0, null);
        }

        void set(String label, String detail, int drawable, String description) {
            primary.setText(label);
            secondary.setText(detail);
            secondary.setVisibility(detail.isEmpty() ? GONE : VISIBLE);
            primary.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, drawable, 0);
            setContentDescription(description);
        }
    }

    private void open(LibraryStore.Source item) {
        source = item;
        managing = false;
        enter(new Screen(item.address, item.name));
    }

    private void enter(Screen next) {
        rememberPosition();
        screens.add(next);
        load(false);
    }

    private void rememberPosition() {
        Screen current = screen();
        if (current == null || book != null || page == null || list.getChildCount() == 0) return;
        current.first = list.getFirstVisiblePosition();
        current.offset = list.getChildAt(0).getTop();
    }

    private Locale catalogLocale() {
        Configuration configuration = getResources().getConfiguration();
        if (Build.VERSION.SDK_INT >= 24 && !configuration.getLocales().isEmpty()) {
            return configuration.getLocales().get(0);
        }
        return configuration.locale == null ? Locale.getDefault() : configuration.locale;
    }

    private void load(boolean reload) {
        cancelRequest();
        Screen screen = screen();
        LibraryStore.Source item = source;
        String key = item.id + "\n" + screen.address + (screen.query == null ? "" : "\n" + screen.query);
        page = reload ? null : cache.get(key);
        loadError = null;
        if (page != null) {
            render();
            return;
        }
        if (reload) cache.remove(key);
        Http.Call call = new Http.Call(proxy.forConnection());
        request = call;
        render();
        Locale locale = catalogLocale();
        network.execute(() -> {
            FeedReader.Page loaded = null;
            UiMessage error = null;
            try {
                String address = screen.search == null ? screen.address
                        : FeedReader.search(screen.search, screen.query, item, call);
                loaded = FeedReader.get(address, item, call, locale);
            } catch (Exception failure) {
                error = Http.error(failure, R.string.error_catalog_load);
            }
            FeedReader.Page result = loaded;
            UiMessage problem = error;
            runOnUiThread(() -> {
                if (request != call || call.cancelled()) return;
                request = null;
                page = result;
                loadError = problem;
                if (page != null) cache.put(key, page);
                render();
            });
        });
    }

    private void cancelRequest() {
        if (request != null) {
            request.cancel();
            request = null;
        }
    }

    @Override public void onBackPressed() {
        if (book != null) {
            book = null;
            render();
        } else if (managing || settings) {
            managing = false;
            settings = false;
            render();
        } else if (screen() != null) {
            cancelRequest();
            screens.remove(screens.size() - 1);
            if (screen() == null) {
                source = null;
                page = null;
                loadError = null;
                render();
            } else {
                load(false);
            }
        } else {
            super.onBackPressed();
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = key == KeyEvent.KEYCODE_VOLUME_DOWN || key == KeyEvent.KEYCODE_PAGE_DOWN;
        boolean up = key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_PAGE_UP;
        if ((!down && !up) || list.getVisibility() != View.VISIBLE || list.getChildCount() == 0) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) turnPage(down);
        return true;
    }

    private void turnPage(boolean down) {
        if (down && !list.canScrollList(1)) {
            if (book == null && page != null && page.next != null && !page.next.equals(screen().address)) {
                enter(new Screen(page.next, page.title.isEmpty() ? screen().title : page.title));
            }
            return;
        }
        int step = Math.max(1, list.getLastVisiblePosition() - list.getFirstVisiblePosition());
        int target = list.getFirstVisiblePosition() + (down ? step : -step);
        list.setSelectionFromTop(Math.max(0, Math.min(list.getCount() - 1, target)), 0);
    }

    private void editProxy() {
        Form form = new Form(getString(R.string.network_proxy));
        NetworkProxy.Mode[] modes = NetworkProxy.Mode.values();
        int[] labels = new int[modes.length];
        for (int i = 0; i < modes.length; i++) labels[i] = modes[i].label;
        RadioGroup choices = form.choices(labels, proxy.mode.ordinal());

        LinearLayout endpoint = column();
        form.content.addView(endpoint, new LinearLayout.LayoutParams(MATCH, WRAP));
        EditText host = form.field(endpoint, getString(R.string.proxy_host), proxy.host,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, 253);
        EditText port = form.field(endpoint, getString(R.string.proxy_port), proxy.port == 0 ? "" : Integer.toString(proxy.port),
                InputType.TYPE_CLASS_NUMBER, 5);
        endpoint.setVisibility(proxy.mode.usesEndpoint() ? View.VISIBLE : View.GONE);
        choices.setOnCheckedChangeListener((group, id) ->
                endpoint.setVisibility(modes[choiceIndex(group)].usesEndpoint() ? View.VISIBLE : View.GONE));

        form.button(R.drawable.ic_close, R.string.cancel, form.dialog::dismiss);
        form.button(R.drawable.ic_save, R.string.save, () -> {
            NetworkProxy.Mode mode = modes[choiceIndex(choices)];
            String address = proxy.host;
            int number = proxy.port;
            if (mode.usesEndpoint()) {
                try {
                    address = NetworkProxy.parseHost(host.getText().toString());
                } catch (IllegalArgumentException failure) {
                    form.error(getString(R.string.error_proxy_host), host);
                    return;
                }
                try {
                    number = NetworkProxy.parsePort(port.getText().toString());
                } catch (IllegalArgumentException failure) {
                    form.error(getString(R.string.error_proxy_port), port);
                    return;
                }
            }

            proxy = new NetworkProxy(mode, address, number);
            proxy.save(this);
            cache.evictAll();
            form.dialog.dismiss();
            render();
        });
        form.show(false);
    }

    private void chooseLanguage() {
        Form form = new Form(getString(R.string.language));
        AppLanguage current = AppLanguage.selected(this);
        AppLanguage[] languages = AppLanguage.values();
        int[] labels = new int[languages.length];
        for (int i = 0; i < languages.length; i++) labels[i] = languages[i].label;
        RadioGroup choices = form.choices(labels, current.ordinal());
        form.button(R.drawable.ic_close, R.string.cancel, form.dialog::dismiss);
        form.button(R.drawable.ic_save, R.string.save, () -> {
            AppLanguage selected = languages[choiceIndex(choices)];
            form.dialog.dismiss();
            if (selected == current) return;
            selected.select(this);
            if (Build.VERSION.SDK_INT < 33) recreate();
            else render();
        });
        form.show(false);
    }

    private int choiceIndex(RadioGroup choices) {
        return choices.indexOfChild(choices.findViewById(choices.getCheckedRadioButtonId()));
    }

    private void editSource(LibraryStore.Source original) {
        Form form = new Form(getString(original == null ? R.string.add_catalog : R.string.edit_catalog));
        EditText name = form.field(getString(R.string.catalog_name), original == null ? "" : original.name,
                InputType.TYPE_CLASS_TEXT, 120);
        EditText address = form.field(getString(R.string.catalog_address), original == null ? "" : original.address,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, 4096);
        address.setHint(R.string.url_example);
        EditText username = form.field(getString(R.string.username), original == null ? "" : original.user,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 256);
        EditText password = form.field(getString(R.string.password), original == null ? "" : original.password,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 512);
        if (original != null) {
            form.button(R.drawable.ic_delete, R.string.delete, () -> {
                form.dialog.dismiss();
                confirmDelete(original);
            });
        }
        form.button(R.drawable.ic_close, R.string.cancel, form.dialog::dismiss);
        form.button(R.drawable.ic_save, R.string.save, () -> {
            URL validated;
            try {
                validated = Http.address(address.getText().toString().trim());
            } catch (MalformedURLException failure) {
                form.error(getString(R.string.error_catalog_url), address);
                return;
            }
            String label = name.getText().toString().trim();
            if (label.isEmpty()) label = validated.getHost();
            String user = username.getText().toString().trim();
            if (user.indexOf(':') >= 0) {
                form.error(getString(R.string.error_username_colon), username);
                return;
            }
            String secret = password.getText().toString();
            LibraryStore.Source updated = original == null ?
                    new LibraryStore.Source(label, validated.toExternalForm(), user, secret) :
                    new LibraryStore.Source(original.id, label, validated.toExternalForm(), user, secret);
            library.save(updated);
            cache.evictAll();
            form.dialog.dismiss();
            if (original == null) open(updated);
            else render();
        });
        form.show(false);
    }

    private void confirmDelete(LibraryStore.Source item) {
        Form form = new Form(getString(R.string.remove_catalog));
        form.paragraph(getString(R.string.remove_catalog_prompt, item.name));
        form.button(R.drawable.ic_close, R.string.cancel, form.dialog::dismiss);
        form.button(R.drawable.ic_delete, R.string.delete, () -> {
            library.remove(item);
            cache.evictAll();
            form.dialog.dismiss();
            render();
        });
        form.show(false);
    }

    private void search(FeedReader.Link link) {
        Form form = new Form(getString(R.string.search_catalog));
        EditText input = form.field(getString(R.string.search_query), "", InputType.TYPE_CLASS_TEXT, 256);
        Runnable submit = () -> {
            String query = input.getText().toString().trim();
            if (query.isEmpty()) {
                form.error(getString(R.string.error_search_term), input);
                return;
            }
            form.dialog.dismiss();
            enter(new Screen(link.url, "", link, query));
        };
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener((view, action, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (action != EditorInfo.IME_ACTION_SEARCH && !enter) return false;
            if (event == null || event.getAction() == KeyEvent.ACTION_UP) submit.run();
            return true;
        });
        form.button(R.drawable.ic_close, R.string.cancel, form.dialog::dismiss);
        form.button(R.drawable.ic_search, R.string.search, submit);
        form.show(true);
    }

    private void downloadFolder() {
        Form form = new Form(getString(R.string.download_folder));
        Uri directory = library.folder();
        form.paragraph(directory == null ? getString(R.string.folder_not_set) : folderLabel(directory));
        form.button(R.drawable.ic_back, R.string.back, form.dialog::dismiss);
        if (!DownloadService.isRunning()) {
            form.button(R.drawable.ic_folder_open, R.string.choose_folder, () -> {
                form.dialog.dismiss();
                pickDirectory();
            });
        }
        form.show(false);
    }

    private void requestDownload(FeedReader.Link link, String title) {
        if (DownloadService.isRunning()) return;
        pending = new PendingBook(source, link, title);
        Uri directory = library.folder();
        if (directory != null && hasFolderAccess(directory)) {
            startPending(directory);
        } else {
            pickDirectory();
        }
    }

    private boolean hasFolderAccess(Uri directory) {
        for (UriPermission grant : getContentResolver().getPersistedUriPermissions()) {
            if (grant.getUri().equals(directory) && grant.isReadPermission() && grant.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    private void pickDirectory() {
        if (DownloadService.isRunning()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(DIRECTORY_ACCESS | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        Uri previous = library.folder();
        if (Build.VERSION.SDK_INT >= 26) {
            Uri initial = previous == null
                    ? DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")
                    : previous;
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        }
        try {
            startActivityForResult(intent, PICK_DIRECTORY);
        } catch (ActivityNotFoundException failure) {
            pending = null;
            notice(getString(R.string.error_folder_picker));
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_DIRECTORY) return;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            pending = null;
            return;
        }
        Uri directory = data.getData();
        try {
            if ((data.getFlags() & DIRECTORY_ACCESS) != DIRECTORY_ACCESS) throw new SecurityException();
            getContentResolver().takePersistableUriPermission(directory, DIRECTORY_ACCESS);
            String label = folderLabel(directory);
            library.setFolder(directory);
            if (pending != null) startPending(directory);
            else notice(getString(R.string.folder_changed, label));
        } catch (IllegalArgumentException | SecurityException failure) {
            pending = null;
            notice(getString(R.string.error_folder_access));
        }
    }

    private String folderLabel(Uri directory) {
        String id = DocumentsContract.getTreeDocumentId(directory);
        if ("com.android.externalstorage.documents".equals(directory.getAuthority())) {
            int colon = id.indexOf(':');
            if (colon >= 0) {
                String volume = getString(id.startsWith("primary:") ? R.string.storage_internal : R.string.storage_sd);
                return id.length() == colon + 1 ? volume : volume + "/" + id.substring(colon + 1);
            }
        }
        return id;
    }

    private void startPending(Uri directory) {
        PendingBook selected = pending;
        pending = null;
        notice = "";
        DownloadService.start(this, new DownloadService.Job(
                directory, selected.source, selected.link, selected.title, proxy.forConnection()));
    }

    private void notice(String message) {
        DownloadService.dismiss();
        notice = message;
        onDownloadChanged();
    }

    private void statusAction() {
        if (DownloadService.isRunning()) DownloadService.cancel();
        else {
            notice = "";
            DownloadService.dismiss();
            onDownloadChanged();
        }
    }

    @Override public void onDownloadChanged() {
        boolean running = DownloadService.isRunning();
        String message = DownloadService.message(this);
        String shown = message.isEmpty() ? notice : message;
        if (!TextUtils.equals(status.getText(), shown)) status.setText(shown);
        int visible = shown.isEmpty() ? View.GONE : View.VISIBLE;
        if (statusPanel.getVisibility() != visible) statusPanel.setVisibility(visible);
        if (running != statusRunning) {
            setIcon(statusAction, R.drawable.ic_close, running ? R.string.cancel_download : R.string.close);
            statusRunning = running;
        }
        statusAction.setEnabled(!DownloadService.isCancelling());
    }

    private final class Form {
        final Dialog dialog;
        final LinearLayout content;
        final LinearLayout controls;
        final TextView error;
        EditText first;

        Form(String heading) {
            dialog = new Dialog(MainActivity.this);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            ScrollView scroll = new ScrollView(MainActivity.this);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.setFillViewport(false);
            scroll.setVerticalScrollBarEnabled(false);
            content = column();
            content.setFocusableInTouchMode(true);
            content.setPadding(dp(16), dp(14), dp(16), dp(14));
            content.setBackground(outline(Color.WHITE, 1));
            TextView header = text(heading, 21);
            header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            content.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));
            scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
            dialog.setContentView(scroll);
            error = text("", 15);
            error.setPadding(0, dp(10), 0, 0);
            error.setVisibility(View.GONE);
            controls = row();
            controls.setPadding(0, dp(14), 0, 0);
        }

        EditText field(String label, String initial, int inputType, int maxLength) {
            return field(content, label, initial, inputType, maxLength);
        }

        EditText field(LinearLayout section, String label, String initial, int inputType, int maxLength) {
            EditText field = new EditText(MainActivity.this);
            field.setId(View.generateViewId());
            field.setSingleLine(true);
            field.setInputType(inputType);
            field.setTypeface(Typeface.DEFAULT);
            field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            field.setTextColor(Color.BLACK);
            field.setHintTextColor(Color.BLACK);
            field.setBackground(outlineStates(true));
            field.setBackgroundTintList(null);
            field.setPadding(dp(9), dp(6), dp(9), dp(6));
            field.setMinimumHeight(dp(45));
            field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
            field.setText(initial);
            field.setSelectAllOnFocus(false);
            if (Build.VERSION.SDK_INT >= 26) field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);

            TextView caption = text(label, 15);
            caption.setLabelFor(field.getId());
            caption.setPadding(0, dp(11), 0, dp(4));
            section.addView(caption, new LinearLayout.LayoutParams(MATCH, WRAP));
            section.addView(field, new LinearLayout.LayoutParams(MATCH, WRAP));
            if (first == null) first = field;
            return field;
        }

        void paragraph(String value) {
            TextView body = text(value, 16);
            body.setPadding(0, dp(12), 0, 0);
            content.addView(body, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        void error(String message, EditText field) {
            error.setText(message);
            error.setVisibility(View.VISIBLE);
            field.requestFocus();
        }

        RadioGroup choices(int[] labels, int selected) {
            RadioGroup choices = new RadioGroup(MainActivity.this);
            choices.setOrientation(RadioGroup.VERTICAL);
            choices.setPadding(0, dp(8), 0, 0);
            for (int label : labels) {
                RadioButton option = new RadioButton(MainActivity.this);
                option.setId(View.generateViewId());
                option.setText(label);
                option.setTextColor(Color.BLACK);
                option.setButtonTintList(ColorStateList.valueOf(Color.BLACK));
                option.setMinimumHeight(dp(48));
                option.setBackground(outlineStates(false));
                choices.addView(option, new RadioGroup.LayoutParams(MATCH, WRAP));
            }
            choices.check(choices.getChildAt(selected).getId());
            content.addView(choices, new LinearLayout.LayoutParams(MATCH, WRAP));
            return choices;
        }

        void button(int icon, int label, Runnable click) {
            addButton(controls, icon, label, click);
        }

        void show(boolean keyboard) {
            content.addView(error, new LinearLayout.LayoutParams(MATCH, WRAP));
            content.addView(controls, new LinearLayout.LayoutParams(MATCH, WRAP));
            Window window = dialog.getWindow();
            window.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setWindowAnimations(0);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | (keyboard ? WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    : WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN));
            if (keyboard && first != null) first.requestFocus();
            else content.requestFocus();
            dialog.show();
            window.setLayout(getDialogWidth(), WRAP);
            if (Build.VERSION.SDK_INT >= 29) window.getDecorView().setForceDarkAllowed(false);
        }
    }

    private int getDialogWidth() {
        return Math.min(dp(440), getResources().getDisplayMetrics().widthPixels - dp(24));
    }

    private void action(int icon, int label, Runnable click) {
        addButton(actions, icon, label, click);
    }

    private void addButton(LinearLayout bar, int icon, int label, Runnable click) {
        LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(0, WRAP, 1);
        if (bar.getChildCount() != 0) placement.leftMargin = dp(8);
        bar.addView(button(icon, label, click), placement);
    }

    private ImageButton button(int icon, int label, Runnable click) {
        ImageButton button = new ImageButton(this);
        button.setMinimumWidth(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setStateListAnimator(null);
        button.setBackgroundTintList(null);
        button.setBackground(outlineStates(true));
        button.setFocusable(true);
        setIcon(button, icon, label);
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private void setIcon(ImageButton button, int icon, int label) {
        button.setImageResource(icon);
        String description = getString(label);
        button.setContentDescription(description);
        if (Build.VERSION.SDK_INT >= 26) button.setTooltipText(description);
    }

    private StateListDrawable outlineStates(boolean filled) {
        int background = filled ? Color.WHITE : Color.TRANSPARENT;
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, outline(background, 2));
        states.addState(new int[]{android.R.attr.state_focused}, outline(background, 2));
        states.addState(new int[]{android.R.attr.state_selected}, outline(background, 2));
        states.addState(new int[]{}, outline(background, filled ? 1 : 0));
        return states;
    }

    private GradientDrawable outline(int background, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        if (stroke > 0) shape.setStroke(dp(stroke), Color.BLACK);
        return shape;
    }

    private TextView text(String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        text.setTextColor(Color.BLACK);
        return text;
    }

    private LinearLayout row() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        return box;
    }

    private LinearLayout column() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private void rule(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(Color.BLACK);
        parent.addView(line, new LinearLayout.LayoutParams(MATCH, dp(1)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
