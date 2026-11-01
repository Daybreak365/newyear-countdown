package dev.newyear.countdown.ai.extension;

import com.google.gson.JsonObject;
import java.util.Map;

/** Trusted native module SPI. Player wishes may draft modules; only operators may test/install them. */
public interface NativeExtension extends AutoCloseable {
    /** Stable operation name -> exact argument/return documentation. */
    Map<String,String> operations();
    /** Must describe the effect without touching a game/world. Used by WishScript dry runs. */
    JsonObject preview(String operation, JsonObject arguments);
    /** Called on the server thread. Implementations must enforce ownership, bounds and cleanup. */
    JsonObject invoke(String operation, ExtensionContext context, JsonObject arguments);
    /** Executed in the test worker, never during drafting or compilation. Must contain at least one assertion. */
    Map<String,Boolean> selfTest();
    /** Run against the isolated test server and its fake player. Default fails closed. */
    default Map<String,Boolean> integrationTest(ExtensionContext context) {
        throw new UnsupportedOperationException("Implement real world assertions for this extension");
    }
    @Override default void close() {}
}
