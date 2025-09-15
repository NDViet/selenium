// Simple test to verify WebSocket proxy functionality
import java.util.Optional;

class TestWebSocketProxy {
    public static void main(String[] args) {
        // Test session ID extraction from WebSocket URI
        String testUri = "/session/859ad40a88d1eb799d31a43fc28bf7ba/se/vnc";
        Optional<String> sessionId = HttpSessionId.getSessionId(testUri);
        
        System.out.println("Testing WebSocket URI: " + testUri);
        
        if (sessionId.isPresent()) {
            System.out.println("✅ Successfully extracted session ID: " + sessionId.get());
        } else {
            System.out.println("❌ Failed to extract session ID from URI");
        }
        
        // Test various WebSocket URI patterns
        String[] testUris = {
            "/session/abc123/se/vnc",
            "/session/def456/se/cdp",
            "/wd/hub/session/ghi789/se/vnc",
            "/session/jkl012/element/mno345/click",
            "/invalid/uri/without/session"
        };
        
        System.out.println("\nTesting various URI patterns:");
        for (String uri : testUris) {
            Optional<String> id = HttpSessionId.getSessionId(uri);
            System.out.println("URI: " + uri + " -> Session ID: " + 
                (id.isPresent() ? id.get() : "NOT FOUND"));
        }
    }
}
