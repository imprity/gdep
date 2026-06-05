package com.gdep;

import com.dslplatform.json.JsonConverter;
import com.dslplatform.json.JsonReader;
import com.dslplatform.json.JsonWriter;
import java.io.IOException;
import java.time.Instant;

@SuppressWarnings("NullAway")
public class Converters {
    @JsonConverter(target = Instant.class)
    public static class InstantConverter {
        public static Instant read(JsonReader reader) throws IOException {
            if (reader.wasNull()) return null;
            return Instant.parse(reader.readSimpleString());
        }

        public static void write(JsonWriter writer, Instant value) {
            if (value == null) {
                writer.writeNull();
            } else {
                writer.writeString(value.toString());
            }
        }
    }
}
