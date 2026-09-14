package dev.coreai.agent;

import com.google.gson.*;
import java.util.Map;

/** Immutable data at a host boundary. No mutable game or application objects are retained. */
@com.google.gson.annotations.JsonAdapter(Facts.Adapter.class)
public record Facts(String json) {
  private static final Gson JSON = new Gson();

  public Facts {
    JsonElement value = JsonParser.parseString(json);
    if (!value.isJsonObject()) throw new IllegalArgumentException("Facts must be an object");
    json = JSON.toJson(value);
  }

  public static Facts of(Map<String, ?> values) {
    return new Facts(JSON.toJson(values));
  }

  public JsonObject object() {
    return JsonParser.parseString(json).getAsJsonObject();
  }

  public static final class Adapter extends TypeAdapter<Facts> {
    public void write(com.google.gson.stream.JsonWriter writer, Facts value)
        throws java.io.IOException {
      JSON.toJson(value.object(), writer);
    }

    public Facts read(com.google.gson.stream.JsonReader reader) throws java.io.IOException {
      return new Facts(JSON.fromJson(reader, JsonElement.class).toString());
    }
  }
}
