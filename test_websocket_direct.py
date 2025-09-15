#!/usr/bin/env python3
"""
Direct WebSocket test to verify if Load Balancer WebSocket proxy is being invoked
"""

import asyncio
import websockets

async def test_websocket_direct():
    """Test WebSocket connection directly to see if proxy logs appear"""
    
    # Test WebSocket URI with a fake session ID
    websocket_uri = "ws://192.168.2.48:9996/session/3e79f662774fdc3cf5b184171abe6df4/se/vnc"
    
    print(f"Testing WebSocket connection to: {websocket_uri}")
    print("This should generate logs in Load Balancer even if connection fails...")
    
    try:
        # Attempt to connect - this should trigger our WebSocket proxy
        async with websockets.connect(websocket_uri, timeout=5) as websocket:
            print("✅ WebSocket connection established!")
            await websocket.send("test")
            
    except websockets.exceptions.ConnectionClosed as e:
        print(f"❌ WebSocket connection closed: {e}")
        print("This is expected if session doesn't exist, but we should see logs in Load Balancer")
    except websockets.exceptions.WebSocketException as e:
        print(f"❌ WebSocket error: {e}")
        print("This is expected if session doesn't exist, but we should see logs in Load Balancer")
    except Exception as e:
        print(f"❌ Connection error: {e}")
        print("This is expected if session doesn't exist, but we should see logs in Load Balancer")

if __name__ == "__main__":
    print("🔧 Direct WebSocket Test - Checking if Load Balancer WebSocket proxy is invoked")
    print("=" * 80)
    
    asyncio.run(test_websocket_direct())
    
    print("\n" + "=" * 80)
    print("Check Load Balancer logs for:")
    print("- 'Load Balancer WebSocket proxy received request for URI'")
    print("- 'Extracted session ID'") 
    print("- 'WEBSOCKET CONNECTION REJECTED'")
    print("If no logs appear, WebSocket handler is not registered properly!")
