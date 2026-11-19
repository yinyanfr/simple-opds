package io.github.einkopds;

import android.text.Html;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FeedReader {
    private static final String ACCEPT = "application/atom+xml, application/opds+json, "
            + "application/opds-publication+json, application/json;q=0.8, application/xml;q=0.8, */*;q=0.3";
    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final String OPDS = "http://opds-spec.org/";
    private static final String XML = "http://www.w3.org/XML/1998/namespace";
    private static final int MAX_PAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ROWS = 2000;
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern EXPRESSION = Pattern.compile("\\{([^{}]+)\\}");

    private final Locale locale;
    private final String authorSeparator;

    private FeedReader(Locale locale) {
        this.locale = locale;
        String language = locale.getLanguage();
        authorSeparator = language.equals("zh") || language.equals("ja") ? "、" : ", ";
    }

    static final class Link {
        final String url;
        final String type;
        final String rel;
        final String title;
        final boolean indirect;

        Link(String url, String type, String rel, String title, boolean indirect) {
            this.url = url;
            this.type = Http.mediaType(type);
            this.rel = rel == null ? "" : rel.toLowerCase(Locale.ROOT);
            this.title = tidy(title, 100);
            this.indirect = indirect;
        }

        boolean has(String relation) {
            int position = rel.indexOf(relation);
            while (position >= 0) {
                int end = position + relation.length();
                if ((position == 0 || Character.isWhitespace(rel.charAt(position - 1)))
                        && (end == rel.length() || Character.isWhitespace(rel.charAt(end)))) return true;
                position = rel.indexOf(relation, position + 1);
            }
            return false;
        }

        boolean isSample() {
            return opds("acquisition/sample");
        }

        boolean downloadable() {
            return url != null && !indirect && !isCatalog() && !type.equals("application/vnd.adobe.adept+xml")
                    && !type.equals("application/vnd.readium.lcp.license.v1.0+json")
                    && (opds("acquisition") || opds("acquisition/open-access") || isSample());
        }

        boolean isCatalog() {
            return type.equals("application/atom+xml") || type.equals("application/opds+json")
                    || type.equals("application/opds-publication+json");
        }

        boolean opds(String name) {
            return has(OPDS + name) || has("https://opds-spec.org/" + name);
        }
    }

    static final class Entry {
        final String title;
        final String author;
        final String catalog;
        final List<Link> downloads;
        final boolean groupMore;

        private Entry(String title, String author, String catalog, List<Link> downloads, boolean groupMore) {
            this.title = title;
            this.author = author;
            this.catalog = catalog;
            this.downloads = downloads;
            this.groupMore = groupMore;
        }
    }

    static final class Page {
        final ArrayList<Entry> entries = new ArrayList<>();
        String title = "";
        String next;
        Link search;
        private int entryBytes;

        void add(Entry entry) throws Http.Failure {
            if (entry == null) return;
            if (entries.size() == MAX_ROWS) {
                throw new Http.Failure(R.string.error_catalog_entries);
            }
            entries.add(entry);
            entryBytes += 128 + 2 * (entry.title.length() + entry.author.length()
                    + (entry.catalog == null ? 0 : entry.catalog.length()));
            for (Link link : entry.downloads) {
                entryBytes += 128 + 2 * (link.url.length() + link.type.length() + link.rel.length() + link.title.length());
            }
        }

        int cacheBytes() {
            return Math.max(8192, entryBytes);
        }

        void includeLinks(List<Link> links) {
            for (Link link : links) {
                if (link.url == null) continue;
                if (next == null && link.has("next")) next = link.url;
                if (search == null && link.has("search")) search = link;
            }
        }
    }

    static Page get(String address, LibraryStore.Source source, Http.Call call, Locale locale)
            throws IOException, XmlPullParserException {
        FeedReader reader = new FeedReader(locale);
        try (Http.Response response = Http.get(address, source, ACCEPT, true, call);
             BufferedInputStream input = new BufferedInputStream(
                     Http.bounded(response.stream(), MAX_PAGE_BYTES, call), 8192)) {
            String base = response.url.toExternalForm();
            input.mark(4);
            int first = input.read();
            if (first == 0xef && input.read() == 0xbb && input.read() == 0xbf) {
                first = input.read();
            } else {
                input.reset();
                first = input.read();
            }
            while (first == ' ' || first == '\n' || first == '\r' || first == '\t') {
                first = input.read();
            }
            if (first < 0) throw new Http.Failure(R.string.error_catalog_no_content);
            java.io.PushbackInputStream body = new java.io.PushbackInputStream(input, 1);
            body.unread(first);
            String type = Http.mediaType(response.header("Content-Type"));
            if (first == '{' || type.endsWith("+json") || type.equals("application/json")) {
                try (JsonReader json = new JsonReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    return reader.jsonPage(json, base);
                }
            }
            return reader.xmlPage(body, base);
        }
    }

    private static Entry makeEntry(String title, String author, List<Link> links, boolean navigation) {
        String catalog = null;
        ArrayList<Link> downloads = null;
        for (Link link : links) {
            if (link.url == null || link.has("search") || link.has("self")) continue;
            if (link.downloadable()) {
                if (downloads == null) downloads = new ArrayList<>();
                boolean duplicate = false;
                for (Link previous : downloads) {
                    if (previous.url.equals(link.url)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) downloads.add(link);
            } else if (catalog == null && (link.isCatalog() || link.opds("subsection")
                    || link.has("subsection") || (navigation && link.type.isEmpty()))) {
                catalog = link.url;
            }
        }
        if (title.isEmpty() && catalog == null && downloads == null) return null;
        return new Entry(title, author, catalog, downloads == null ? Collections.emptyList() : downloads, false);
    }

    private Page xmlPage(InputStream input, String base) throws IOException, XmlPullParserException {
        XmlPullParser xml = xml(input);
        Page page = new Page();
        base = xmlBase(xml, base);
        if (atom(xml, "entry")) {
            Entry entry = xmlEntry(xml, base);
            page.add(entry);
            if (entry != null) page.title = entry.title;
            return page;
        }
        if (!atom(xml, "feed")) throw new Http.Failure(R.string.error_not_opds);
        int root = xml.getDepth();
        ArrayList<Link> pageLinks = new ArrayList<>();
        while (nextChild(xml, root)) {
            String childBase = xmlBase(xml, base);
            if (atom(xml, "title")) {
                page.title = xmlText(xml, 200);
            } else if (atom(xml, "entry")) {
                page.add(xmlEntry(xml, childBase));
            } else if (atom(xml, "link")) {
                addLink(pageLinks, xmlLink(xml, childBase));
            } else {
                skip(xml);
            }
        }
        page.includeLinks(pageLinks);
        return page;
    }

    private Entry xmlEntry(XmlPullParser xml, String base) throws IOException, XmlPullParserException {
        String title = "";
        String author = "";
        ArrayList<Link> links = new ArrayList<>();
        int root = xml.getDepth();
        while (nextChild(xml, root)) {
            if (atom(xml, "title")) {
                title = xmlText(xml, 200);
            } else if (atom(xml, "author")) {
                String name = "";
                int authorDepth = xml.getDepth();
                while (nextChild(xml, authorDepth)) {
                    if (atom(xml, "name")) name = xmlText(xml, 120);
                    else skip(xml);
                }
                author = joinAuthors(author, name);
            } else if ("creator".equals(xml.getName())
                    && xml.getNamespace().startsWith("http://purl.org/dc/")) {
                author = joinAuthors(author, xmlText(xml, 120));
            } else if (atom(xml, "link")) {
                addLink(links, xmlLink(xml, xmlBase(xml, base)));
            } else {
                skip(xml);
            }
        }
        return makeEntry(title, author, links, false);
    }

    private static Link xmlLink(XmlPullParser xml, String base) throws IOException, XmlPullParserException {
        String href = resolve(base, attribute(xml, "href"));
        String type = attribute(xml, "type");
        String rel = attribute(xml, "rel");
        String title = attribute(xml, "title");
        boolean indirect = false;
        int root = xml.getDepth();
        while (nextChild(xml, root)) {
            if ("indirectAcquisition".equals(xml.getName())) indirect = true;
            skip(xml);
        }
        return new Link(href, type, rel, title, indirect);
    }

    private static XmlPullParser xml(InputStream input) throws IOException, XmlPullParserException {
        XmlPullParser xml = Xml.newPullParser();
        xml.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        xml.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
        xml.setInput(input, null);
        while (xml.getEventType() != XmlPullParser.START_TAG) {
            if (xml.next() == XmlPullParser.END_DOCUMENT) throw new Http.Failure(R.string.error_xml_empty);
        }
        return xml;
    }

    private static boolean atom(XmlPullParser xml, String tag) {
        return tag.equals(xml.getName()) && (ATOM.equals(xml.getNamespace()) || xml.getNamespace().isEmpty());
    }

    private static boolean nextChild(XmlPullParser xml, int root) throws IOException, XmlPullParserException {
        int event;
        do {
            event = xml.next();
            if (event == XmlPullParser.END_DOCUMENT) throw new Http.Failure(R.string.error_xml_incomplete);
        } while (event != XmlPullParser.START_TAG && !(event == XmlPullParser.END_TAG && xml.getDepth() == root));
        return event == XmlPullParser.START_TAG;
    }

    private static void skip(XmlPullParser xml) throws IOException, XmlPullParserException {
        int root = xml.getDepth();
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.getEventType() == XmlPullParser.END_TAG && xml.getDepth() == root) return;
        }
        throw new Http.Failure(R.string.error_xml_incomplete);
    }

    @SuppressWarnings("deprecation")
    private static String xmlText(XmlPullParser xml, int limit) throws IOException, XmlPullParserException {
        boolean html = "html".equals(attribute(xml, "type"));
        int root = xml.getDepth();
        StringBuilder text = new StringBuilder();
        int cap = html ? 1024 : limit;
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.getEventType() == XmlPullParser.END_TAG && xml.getDepth() == root) {
                return tidy(html ? Html.fromHtml(text.toString()).toString() : text.toString(), limit);
            }
            if (xml.getEventType() == XmlPullParser.TEXT && text.length() < cap) {
                String part = xml.getText();
                text.append(part, 0, Math.min(cap - text.length(), part.length()));
            }
        }
        throw new Http.Failure(R.string.error_xml_incomplete);
    }

    private static String xmlBase(XmlPullParser xml, String base) {
        String resolved = resolve(base, xml.getAttributeValue(XML, "base"));
        return resolved == null ? base : resolved;
    }

    private static String attribute(XmlPullParser xml, String name) {
        return xml.getAttributeValue(null, name);
    }

    private Page jsonPage(JsonReader json, String base) throws IOException {
        if (json.peek() != JsonToken.BEGIN_OBJECT) throw new Http.Failure(R.string.error_not_opds);
        Page page = new Page();
        json.beginObject();
        Metadata metadata = new Metadata();
        List<Link> links = Collections.emptyList();
        boolean recognized = false;
        while (json.hasNext()) {
            switch (json.nextName()) {
                case "metadata": metadata = metadata(json); break;
                case "links": links = links(json, base); break;
                case "publications":
                    jsonEntries(json, base, page, false);
                    recognized = true;
                    break;
                case "navigation":
                    jsonEntries(json, base, page, true);
                    recognized = true;
                    break;
                case "groups":
                    groups(json, base, page);
                    recognized = true;
                    break;
                default: json.skipValue();
            }
        }
        json.endObject();
        page.title = metadata.title;
        page.includeLinks(links);
        if (!recognized) {
            Entry publication = makeEntry(metadata.title, metadata.author, links, false);
            if (publication == null || publication.downloads.isEmpty() && publication.catalog == null) {
                if (metadata.title.isEmpty() || links.isEmpty()) {
                    throw new Http.Failure(R.string.error_not_opds);
                }
            } else {
                page.add(publication);
            }
        }
        return page;
    }

    private void groups(JsonReader json, String base, Page page) throws IOException {
        if (!startArray(json)) return;
        while (json.hasNext()) {
            if (!startObject(json)) continue;
            Metadata metadata = new Metadata();
            List<Link> links = Collections.emptyList();
            while (json.hasNext()) {
                switch (json.nextName()) {
                    case "metadata": metadata = metadata(json); break;
                    case "navigation": jsonEntries(json, base, page, true); break;
                    case "publications": jsonEntries(json, base, page, false); break;
                    case "links": links = links(json, base); break;
                    default: json.skipValue();
                }
            }
            json.endObject();
            for (Link link : links) {
                if (link.url != null && (link.has("self") || link.opds("subsection"))) {
                    page.add(new Entry(metadata.title, "", link.url, Collections.emptyList(), true));
                    break;
                }
            }
        }
        json.endArray();
    }

    private void jsonEntries(JsonReader json, String base, Page page, boolean navigation) throws IOException {
        if (!startArray(json)) return;
        while (json.hasNext()) {
            if (navigation) {
                Link link = jsonLink(json, base);
                if (link != null) page.add(makeEntry(link.title, "", Collections.singletonList(link), true));
                continue;
            }
            if (!startObject(json)) continue;
            Metadata metadata = new Metadata();
            List<Link> links = Collections.emptyList();
            while (json.hasNext()) {
                switch (json.nextName()) {
                    case "metadata": metadata = metadata(json); break;
                    case "links": links = links(json, base); break;
                    default: json.skipValue();
                }
            }
            json.endObject();
            page.add(makeEntry(metadata.title, metadata.author, links, false));
        }
        json.endArray();
    }

    private List<Link> links(JsonReader json, String base) throws IOException {
        if (!startArray(json)) return Collections.emptyList();
        ArrayList<Link> links = new ArrayList<>();
        while (json.hasNext()) addLink(links, jsonLink(json, base));
        json.endArray();
        return links;
    }

    private static void addLink(List<Link> links, Link link) {
        if (link != null && link.url != null && links.size() < 48
                && (link.downloadable() || link.isCatalog() || link.type.isEmpty() || link.has("next")
                || link.has("search") || link.has("self") || link.has("subsection") || link.opds("subsection"))) {
            links.add(link);
        }
    }

    private Link jsonLink(JsonReader json, String base) throws IOException {
        if (!startObject(json)) return null;
        String href = null, type = "", rel = "", title = "";
        boolean indirect = false;
        while (json.hasNext()) {
            switch (json.nextName()) {
                case "href": href = string(json, 4096); break;
                case "type": type = string(json, 140); break;
                case "rel": rel = relations(json); break;
                case "title": title = localized(json, 200); break;
                case "properties":
                    if (startObject(json)) {
                        while (json.hasNext()) {
                            if ("indirectAcquisition".equals(json.nextName())) {
                                if (startArray(json)) {
                                    indirect = json.hasNext();
                                    while (json.hasNext()) json.skipValue();
                                    json.endArray();
                                }
                            } else {
                                json.skipValue();
                            }
                        }
                        json.endObject();
                    }
                    break;
                default: json.skipValue();
            }
        }
        json.endObject();
        return new Link(resolve(base, href), type, rel, title, indirect);
    }

    private static String relations(JsonReader json) throws IOException {
        if (json.peek() != JsonToken.BEGIN_ARRAY) return string(json, 320);
        StringBuilder values = new StringBuilder();
        json.beginArray();
        while (json.hasNext()) {
            String relation = string(json, 160);
            if (values.length() < 640) values.append(relation).append(' ');
        }
        json.endArray();
        return values.toString();
    }

    private static final class Metadata {
        String title = "";
        String author = "";
    }

    private Metadata metadata(JsonReader json) throws IOException {
        Metadata data = new Metadata();
        if (!startObject(json)) return data;
        while (json.hasNext()) {
            switch (json.nextName()) {
                case "title": data.title = localized(json, 200); break;
                case "author": data.author = people(json); break;
                default: json.skipValue();
            }
        }
        json.endObject();
        return data;
    }

    private String people(JsonReader json) throws IOException {
        if (json.peek() != JsonToken.BEGIN_ARRAY) return person(json);
        String names = "";
        json.beginArray();
        while (json.hasNext()) names = joinAuthors(names, person(json));
        json.endArray();
        return names;
    }

    private String person(JsonReader json) throws IOException {
        if (json.peek() != JsonToken.BEGIN_OBJECT) return localized(json, 120);
        String name = "";
        json.beginObject();
        while (json.hasNext()) {
            if ("name".equals(json.nextName())) name = localized(json, 120);
            else json.skipValue();
        }
        json.endObject();
        return name;
    }

    private String localized(JsonReader json, int limit) throws IOException {
        if (json.peek() != JsonToken.BEGIN_OBJECT) return tidy(string(json, limit), limit);
        String selected = "";
        int best = -1;
        json.beginObject();
        while (json.hasNext()) {
            String tag = json.nextName();
            String value = tidy(string(json, limit), limit);
            if (value.isEmpty()) continue;
            int score = languageScore(Locale.forLanguageTag(tag), locale);
            if (score > best) {
                selected = value;
                best = score;
            }
        }
        json.endObject();
        return selected;
    }

    private static int languageScore(Locale candidate, Locale preferred) {
        if (candidate.getLanguage().isEmpty() || !candidate.getLanguage().equals(preferred.getLanguage())) return 0;
        if (candidate.equals(preferred)) return 4;
        String desiredScript = script(preferred);
        if (!desiredScript.isEmpty() && desiredScript.equals(script(candidate))) return 3;
        return candidate.getScript().isEmpty() && candidate.getCountry().isEmpty() ? 2 : 1;
    }

    private static String script(Locale locale) {
        if (!locale.getScript().isEmpty() || !locale.getLanguage().equals("zh")) return locale.getScript();
        switch (locale.getCountry()) {
            case "CN":
            case "SG":
                return "Hans";
            case "TW":
            case "HK":
            case "MO":
                return "Hant";
            default:
                return "";
        }
    }

    private static boolean startObject(JsonReader json) throws IOException {
        if (json.peek() == JsonToken.BEGIN_OBJECT) {
            json.beginObject();
            return true;
        }
        json.skipValue();
        return false;
    }

    private static boolean startArray(JsonReader json) throws IOException {
        if (json.peek() == JsonToken.BEGIN_ARRAY) {
            json.beginArray();
            return true;
        }
        json.skipValue();
        return false;
    }

    private static String string(JsonReader json, int limit) throws IOException {
        if (json.peek() != JsonToken.STRING) {
            json.skipValue();
            return "";
        }
        String value = json.nextString();
        return cut(value, limit);
    }

    private String joinAuthors(String existing, String name) {
        if (name.isEmpty() || existing.equals(name) || existing.length() >= 150) return existing;
        return cut(existing.isEmpty() ? name : existing + authorSeparator + name, 150);
    }

    private static String tidy(String value, int limit) {
        if (value == null) return "";
        return cut(SPACE.matcher(value.replace('\ufffc', ' ')).replaceAll(" ").trim(), limit);
    }

    private static String cut(String value, int limit) {
        if (value.length() <= limit) return value;
        if (Character.isHighSurrogate(value.charAt(limit - 1))) limit--;
        return value.substring(0, limit);
    }

    private static String resolve(String base, String href) {
        return href == null || href.length() > 4096 ? null : Http.resolve(base, href);
    }

    static String search(Link search, String query, LibraryStore.Source source, Http.Call call)
            throws IOException, XmlPullParserException {
        String template = search.url;
        if (!template.contains("{")) {
            try (Http.Response response = Http.get(template, source,
                    "application/opensearchdescription+xml, application/xml, */*;q=0.4", true, call);
                 InputStream input = Http.bounded(response.stream(), 256 * 1024, call)) {
                XmlPullParser xml = xml(input);
                String base = xmlBase(xml, response.url.toExternalForm());
                int root = xml.getDepth();
                template = null;
                while (nextChild(xml, root)) {
                    if ("Url".equals(xml.getName())) {
                        String type = Http.mediaType(attribute(xml, "type"));
                        if (type.equals("application/atom+xml") || type.equals("application/opds+json")) {
                            template = resolve(xmlBase(xml, base), attribute(xml, "template"));
                            if (template != null) break;
                        }
                    }
                    skip(xml);
                }
            }
        }
        if (template == null) throw new Http.Failure(R.string.error_search_unavailable);
        return expandSearch(template, query);
    }

    private static String expandSearch(String template, String query) throws IOException {
        String encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20").replace("%7E", "~");
        Matcher matcher = EXPRESSION.matcher(template);
        StringBuffer result = new StringBuffer();
        boolean used = false;
        while (matcher.find()) {
            String expression = matcher.group(1);
            char first = expression.charAt(0);
            if (first == '?' || first == '&') {
                StringBuilder parameters = new StringBuilder();
                for (String key : expression.substring(1).split(",")) {
                    if (!queryVariable(key)) continue;
                    parameters.append(parameters.length() == 0 ? first : '&')
                            .append(key).append('=').append(encoded);
                    used = true;
                }
                matcher.appendReplacement(result, Matcher.quoteReplacement(parameters.toString()));
                continue;
            }
            String variable = expression.replaceFirst("^\\+", "").replaceFirst("\\?$", "");
            String value = "";
            if (queryVariable(variable)) {
                value = encoded;
                used = true;
            } else if ("inputEncoding".equals(variable) || "outputEncoding".equals(variable)) {
                value = "UTF-8";
            } else if ("count".equals(variable)) {
                value = "30";
            } else if ("startIndex".equals(variable) || "startPage".equals(variable)) {
                value = "1";
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        if (!used) throw new Http.Failure(R.string.error_search_template);
        return Http.address(result.toString()).toExternalForm();
    }

    private static boolean queryVariable(String key) {
        return "searchTerms".equals(key) || "query".equals(key) || "q".equals(key) || "search".equals(key);
    }
}
