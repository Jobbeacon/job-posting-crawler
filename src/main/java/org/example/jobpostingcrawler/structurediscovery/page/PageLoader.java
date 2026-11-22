package org.example.jobpostingcrawler.structurediscovery.page;

import org.jsoup.nodes.Document;

/** Loads a rendered page. Implementations are not required to be thread-safe. */
public interface PageLoader extends AutoCloseable {

    Document load(String url);

    @Override
    default void close() {
    }
}
