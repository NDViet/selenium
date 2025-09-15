#!/usr/bin/env python3
"""
Test WebSocket connection to a valid session created through Load Balancer
"""

import asyncio
import websockets

async def test_websocket_valid_session():
    """Test WebSocket connection to the valid session we just created"""
    
    # Use the valid session ID we just created
    session_id = "449423525ccd56c647293cb1d4a3e3fc"
    websocket_uri = f"ws://localhost:9997/session/{session_id}/se/vnc"
    
    print(f"Testing WebSocket connection to VALID session: {session_id}")
    print(f"WebSocket URI: {websocket_uri}")
    print("This should trigger our WebSocket proxy since the session exists...")
    
    try:
        # Attempt to connect with proper WebSocket handshake
        print("Attempting WebSocket connection...")
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
        print("Connection was established but then closed - check Load Balancer logs!")
    except websockets.exceptions.InvalidStatusCode as e:
        print(f"❌ WebSocket handshake failed with status: {e}")
        print("Server rejected the WebSocket upgrade - check Load Balancer logs!")
    except websockets.exceptions.WebSocketException as e:
        print(f"❌ WebSocket error: {e}")
        print("WebSocket protocol error - check Load Balancer logs!")
    except Exception as e:
        print(f"❌ Connection error: {e}")
        print(f"Error type: {type(e).__name__}")

if __name__ == "__main__":
    print("🔧 Valid Session WebSocket Test")
    print("=" * 80)
    
    asyncio.run(test_websocket_valid_session())
    
    print("\n" + "=" * 80)
    print("Check Load Balancer logs for:")
    print("- 'Load Balancer WebSocket proxy received request for URI'")
    print("- 'Extracted session ID: 4f89412e956b02592e4772f80ef7d772'") 
    print("- 'Found Grid instance for session'")
    print("- 'Establishing WebSocket connection to Grid instance'")
    print("If no logs appear, WebSocket handshake is failing before reaching our proxy!")
