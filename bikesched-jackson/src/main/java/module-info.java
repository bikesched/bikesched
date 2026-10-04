/**
 * Jackson integration for bikesched: {@link org.bikesched.jackson.JacksonJsonMapper} maps payloads,
 * step results and event payloads with Jackson 3.
 */
module org.bikesched.jackson {
  requires transitive org.bikesched;
  requires transitive tools.jackson.databind;

  exports org.bikesched.jackson;
}
