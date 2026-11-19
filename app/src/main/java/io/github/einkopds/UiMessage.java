package io.github.einkopds;

import android.content.Context;

final class UiMessage {
    private final int resource;
    private final Object[] arguments;

    UiMessage(int resource, Object... arguments) {
        this.resource = resource;
        this.arguments = arguments;
    }

    String text(Context context) {
        return arguments.length == 0 ? context.getString(resource) : context.getString(resource, arguments);
    }
}
