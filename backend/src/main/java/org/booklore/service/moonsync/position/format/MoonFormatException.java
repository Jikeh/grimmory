package org.booklore.service.moonsync.position.format;

/**
 * A Moon+ {@code .po} or {@code .an} file could not be read. The message names the file kind and what was wrong.
 */
public class MoonFormatException extends IllegalArgumentException {

    public MoonFormatException(String message) {
        super(message);
    }

    public MoonFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
