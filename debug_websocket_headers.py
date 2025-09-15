#!/usr/bin/env python3
"""
Debug WebSocket headers sent by Python websockets library
"""

import asyncio
import websockets
import socket

def test_websocket_headers_manual():
    """Test WebSocket headers by manually creating the HTTP upgrade request"""
    
    session_id = "449423525ccd56c647293cb1d4a3e3fc"
    
    # Create raw HTTP WebSocket upgrade request
    request = f"""GET /session/{session_id}/se/vnc HTTP/1.1\r
Host: localhost:9997\r
Upgrade: websocket\r
Connection: Upgrade\r
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r
Sec-WebSocket-Version: 13\r
\r
"""
    
    print("🔍 Manual WebSocket Headers Test")
    print("=" * 80)
    print("Sending raw HTTP WebSocket upgrade request:")
    print(request)
    
    try:
        # Connect to Load Balancer
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.connect(('localhost', 9997))
        
        # Send the WebSocket upgrade request
        sock.send(request.encode('utf-8'))
        
        # Read response
        response = sock.recv(4096).decode('utf-8')
        print("Response received:")
        print(response)
        
        sock.close()
        
    except Exception as e:
        print(f"❌ Error: {e}")

async def test_websocket_headers_library():
    """Test WebSocket headers using websockets library with debug"""
    
    session_id = "449423525ccd56c647293cb1d4a3e3fc"
    websocket_uri = f"ws://localhost:9997/session/{session_id}/se/vnc"
    
    print("\n🔍 Python websockets Library Headers Test")
    print("=" * 80)
    print(f"WebSocket URI: {websocket_uri}")
    
    try:
        # Enable debug logging for websockets
        import logging
        logging.basicConfig(level=logging.DEBUG)
        logger = logging.getLogger('websockets')
        logger.setLevel(logging.DEBUG)
        
        async with websockets.connect(
            websocket_uri, 
            ping_interval=None,
            close_timeout=5,
            open_timeout=10
        ) as websocket:
            print("✅ WebSocket connection established!")
            
    except Exception as e:
        print(f"❌ WebSocket error: {e}")
        print(f"Error type: {type(e).__name__}")

if __name__ == "__main__":
    print("🔧 WebSocket Headers Debug Test")
    print("=" * 80)
    
    # Test 1: Manual HTTP request to see exact headers
    test_websocket_headers_manual()
    
    # Test 2: Python websockets library with debug logging
    asyncio.run(test_websocket_headers_library())
    
    print("\n" + "=" * 80)
    print("Check Load Balancer logs for WebSocket header validation!")
