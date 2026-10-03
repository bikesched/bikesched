package bikesched.core.event;

import java.util.UUID;

/**
 * A step that received a published event, see {@link EventBuilder#publish()}.
 *
 * @param jobId the id of the job waiting in the step; the job is resumed
 * @param stepName the name of the step that waited for the event
 */
public record ReceivedEvent(UUID jobId, String stepName) {}
