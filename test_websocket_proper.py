#!/usr/bin/env python3
"""
Proper WebSocket test using websockets library to complete full handshake
"""

import asyncio
import websockets
import logging

# Enable debug logging to see WebSocket handshake details
logging.basicConfig(level=logging.DEBUG)

async def test_websocket_proper():
    """Test WebSocket connection with proper handshake protocol"""
    
    # Test WebSocket URI with a fake session ID
    websocket_uri = "ws://192.168.2.48:9996/session/3e79f662774fdc3cf5b184171abe6df4/se/vnc"
    
    print(f"Testing WebSocket connection to: {websocket_uri}")
    print("This should complete the WebSocket handshake and trigger our proxy...")
    
    try:
        # Attempt to connect with proper WebSocket handshake
        async with websockets.connect(
            websocket_uri, 
            ping_interval=None,  # Disable ping/pong
            close_timeout=5,
            open_timeout=10
        ) as websocket:
            print("✅ WebSocket connection established!")
            print("Sending test message...")
            await websocket.send("test message")
            
            # Try to receive a response
            try:
                response = await asyncio.wait_for(websocket.recv(), timeout=5)
                print(f"📨 Received response: {response}")
            except asyncio.TimeoutError:
                print("⏰ No response received (timeout)")
            
    except websockets.exceptions.ConnectionClosedError as e:
        print(f"🔌 WebSocket connection closed: {e}")
        print("This is expected if session doesn't exist, but we should see logs in Load Balancer")
    except websockets.exceptions.InvalidStatusCode as e:
        print(f"❌ WebSocket handshake failed with status: {e}")
        print("This indicates the server rejected the WebSocket upgrade")
    except websockets.exceptions.WebSocketException as e:
        print(f"❌ WebSocket error: {e}")
        print("This is expected if session doesn't exist, but we should see logs in Load Balancer")
    except Exception as e:
        print(f"❌ Connection error: {e}")
        print("This is expected if session doesn't exist, but we should see logs in Load Balancer")

if __name__ == "__main__":
    print("🔧 Proper WebSocket Test - Testing full WebSocket handshake protocol")
    print("=" * 80)
    
    asyncio.run(test_websocket_proper())
    
    print("\n" + "=" * 80)
    print("Check Load Balancer logs for:")
    print("- 'Load Balancer WebSocket proxy received request for URI'")
    print("- 'Extracted session ID'") 
    print("- 'WEBSOCKET CONNECTION REJECTED'")
    print("If no logs appear, WebSocket handshake is failing before reaching our proxy!")
