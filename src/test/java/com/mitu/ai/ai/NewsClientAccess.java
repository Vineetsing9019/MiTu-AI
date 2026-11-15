package com.mitu.ai.ai;

/** Lets tests outside this package see which news feed a question uses. */
public final class NewsClientAccess {
    private NewsClientAccess() {}

    public static String feed(String question) {
        return NewsClient.feedUrl(question);
    }
}
