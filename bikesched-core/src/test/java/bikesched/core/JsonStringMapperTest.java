package bikesched.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JsonStringMapperTest {

  private final JsonStringMapper mapper = new JsonStringMapper();

  @Test
  void passesJsonStringsThrough() {
    assertThat(mapper.toJson("{\"a\": 1}")).isEqualTo("{\"a\": 1}");
    assertThat(mapper.fromJson("{\"a\": 1}", String.class)).isEqualTo("{\"a\": 1}");
    assertThat(mapper.fromJson("[1, 2]", CharSequence.class)).isEqualTo("[1, 2]");
    assertThat(mapper.fromJson("42", Object.class)).isEqualTo("42");
  }

  @Test
  void rejectsOtherTypes() {
    assertThatThrownBy(() -> mapper.toJson(42))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "JsonStringMapper only supports JSON strings, not java.lang.Integer; "
                + "configure a JsonMapper for other types via Bikesched.builder().jsonMapper(...)");
    assertThatThrownBy(() -> mapper.fromJson("{}", PaymentJsonMapper.Payment.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not bikesched.core.PaymentJsonMapper$Payment");
  }
}
