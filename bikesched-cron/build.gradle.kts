plugins {
    id("bikesched.java-library")
}

description = "Cron expressions for bikesched: CronRecurrence and a RecurrenceParser, via cron-utils"

dependencies {
    api(project(":bikesched-core"))
    // Part of the API: CronRecurrence and CronRecurrenceParser take a cron-utils CronType.
    api(libs.cron.utils)

    testImplementation(project(":bikesched-annotations"))
    testImplementation(project(":bikesched-test"))
    testImplementation(libs.postgresql)
}
