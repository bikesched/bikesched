package bikesched.core.schema;

import bikesched.core.BikeschedException;
import java.io.Serial;

/** Thrown when the bikesched schema cannot be migrated or is inconsistent with this library version. */
public class SchemaMigrationException extends BikeschedException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message
     */
    public SchemaMigrationException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public SchemaMigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
