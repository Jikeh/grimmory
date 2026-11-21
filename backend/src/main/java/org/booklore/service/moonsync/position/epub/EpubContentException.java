package org.booklore.service.moonsync.position.epub;

/**
 * An EPUB's structure or content could not be read (bad zip, missing container or package document, a spine item
 * without a manifest entry, content that isn't UTF-8). Callers degrade to a percent-only position, as readsync does
 * with its {@code EpubContentError}.
 */
public class EpubContentException extends RuntimeException {

    public EpubContentException(String message) {
        super(message);
    }

    public EpubContentException(String message, Throwable cause) {
        super(message, cause);
    }
}
