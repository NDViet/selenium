#!/usr/bin/env python3
"""
Simple WebSocket test script to verify Load Balancer WebSocket proxy functionality.
"""

import asyncio
import websockets
import json

async def test_websocket_connection():
    """Test WebSocket connection to Load Balancer"""
    
    # Test WebSocket URI (using the same session ID from the logs)
    websocket_uri = "ws://192.168.2.48:9996/session/859ad40a88d1eb799d31a43fc28bf7ba/se/vnc"
    
    print(f"Testing WebSocket connection to: {websocket_uri}")
    
    try:
        # Attempt to connect to the WebSocket
        async with websockets.connect(websocket_uri, timeout=10) as websocket:
            print("✅ WebSocket connection established successfully!")
            
            # Send a simple test message
            test_message = {"type": "test", "message": "Hello from Load Balancer WebSocket test"}
            await websocket.send(json.dumps(test_message))
            print(f"📤 Sent test message: {test_message}")
            
            # Try to receive a response (with timeout)
            try:
                response = await asyncio.wait_for(websocket.recv(), timeout=5.0)
                print(f"📥 Received response: {response}")
            except asyncio.TimeoutError:
                print("⏰ No response received within 5 seconds (this might be expected for VNC)")
                
    except websockets.exceptions.ConnectionClosed as e:
        print(f"❌ WebSocket connection closed: {e}")
    except websockets.exceptions.WebSocketException as e:
        print(f"❌ WebSocket error: {e}")
    except Exception as e:
        print(f"❌ Unexpected error: {e}")

async def test_websocket_basic():
    """Test basic WebSocket connection without session"""
    
    # Test basic WebSocket connection to Load Balancer
    websocket_uri = "ws://192.168.2.48:9996/test"
    
    print(f"Testing basic WebSocket connection to: {websocket_uri}")
    
    try:
        async with websockets.connect(websocket_uri, timeout=5) as websocket:
            print("✅ Basic WebSocket connection established!")
            await websocket.send("test")
            
    except Exception as e:
        print(f"❌ Basic WebSocket connection failed: {e}")

if __name__ == "__main__":
    print("🔧 Load Balancer WebSocket Proxy Test")
    print("=" * 50)
    
    # Test basic WebSocket connection first
    print("\n1. Testing basic WebSocket connection...")
    asyncio.run(test_websocket_basic())
    
    # Test session-based WebSocket connection
    print("\n2. Testing session-based WebSocket connection...")
    asyncio.run(test_websocket_connection())
    
    print("\n✅ WebSocket tests completed!")
