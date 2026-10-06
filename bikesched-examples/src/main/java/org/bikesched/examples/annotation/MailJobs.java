package org.bikesched.examples.annotation;

import org.bikesched.annotation.JobHandler;
import org.bikesched.job.Job;

/**
 * Job handlers declared as annotated methods. Each method takes the {@link Job}, the payload, both
 * or neither.
 */
public final class MailJobs {

  /** The job type of {@link #sendMail(Job, Mail)}. */
  public static final String SEND_MAIL = "send-mail";

  /** The job type of {@link #archiveMail(Mail)}. */
  public static final String ARCHIVE_MAIL = "archive-mail";

  /** The job type of {@link #checkMailbox()}. */
  public static final String CHECK_MAILBOX = "check-mailbox";

  /** The payload of the mail jobs. */
  public record Mail(String to, String subject) {}

  /** The job and the payload. */
  @JobHandler(type = MailJobs.SEND_MAIL)
  public void sendMail(Job job, Mail mail) {
    System.out.println("Job " + job.id() + ": sending '" + mail.subject() + "' to " + mail.to());
  }

  /** Only the payload. */
  @JobHandler(type = MailJobs.ARCHIVE_MAIL)
  public void archiveMail(Mail mail) {
    System.out.println("Archiving '" + mail.subject() + "'");
  }

  /** Neither. */
  @JobHandler(type = MailJobs.CHECK_MAILBOX)
  public void checkMailbox() {
    System.out.println("Checking the mailbox");
  }
}
