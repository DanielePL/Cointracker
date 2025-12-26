"""
WebSocket Manager for Real-Time Price Streaming
Connects to Binance WebSocket and broadcasts to all connected clients
"""

import asyncio
import json
from typing import Dict, List, Set
from fastapi import WebSocket
from loguru import logger
import websockets
from datetime import datetime


class ConnectionManager:
    """Manages WebSocket connections from Android clients"""

    def __init__(self):
        self.active_connections: List[WebSocket] = []
        self.subscriptions: Dict[str, Set[WebSocket]] = {}  # symbol -> connections

    async def connect(self, websocket: WebSocket):
        await websocket.accept()
        self.active_connections.append(websocket)
        logger.info(f"Client connected. Total connections: {len(self.active_connections)}")

    def disconnect(self, websocket: WebSocket):
        if websocket in self.active_connections:
            self.active_connections.remove(websocket)
        # Remove from all subscriptions
        for symbol in self.subscriptions:
            self.subscriptions[symbol].discard(websocket)
        logger.info(f"Client disconnected. Total connections: {len(self.active_connections)}")

    def subscribe(self, websocket: WebSocket, symbol: str):
        if symbol not in self.subscriptions:
            self.subscriptions[symbol] = set()
        self.subscriptions[symbol].add(websocket)
        logger.info(f"Client subscribed to {symbol}")

    def unsubscribe(self, websocket: WebSocket, symbol: str):
        if symbol in self.subscriptions:
            self.subscriptions[symbol].discard(websocket)

    async def broadcast_to_symbol(self, symbol: str, data: dict):
        """Send data to all clients subscribed to a symbol"""
        if symbol not in self.subscriptions:
            return

        dead_connections = []
        for connection in self.subscriptions[symbol]:
            try:
                await connection.send_json(data)
            except Exception as e:
                logger.error(f"Error sending to client: {e}")
                dead_connections.append(connection)

        # Clean up dead connections
        for conn in dead_connections:
            self.disconnect(conn)

    async def broadcast_all(self, data: dict):
        """Send data to all connected clients"""
        dead_connections = []
        for connection in self.active_connections:
            try:
                await connection.send_json(data)
            except Exception as e:
                dead_connections.append(connection)

        for conn in dead_connections:
            self.disconnect(conn)


class BinanceWebSocketClient:
    """
    Connects to Binance WebSocket streams for real-time data
    """

    BINANCE_WS_URL = "wss://stream.binance.com:9443/ws"
    BINANCE_TESTNET_WS_URL = "wss://testnet.binance.vision/ws"

    def __init__(self, manager: ConnectionManager, testnet: bool = True):
        self.manager = manager
        self.testnet = testnet
        self.ws_url = self.BINANCE_TESTNET_WS_URL if testnet else self.BINANCE_WS_URL
        self.running = False
        self.subscribed_streams: List[str] = []

    def _symbol_to_stream(self, symbol: str) -> str:
        """Convert BTC/USDT to btcusdt"""
        return symbol.replace("/", "").lower()

    async def start_ticker_stream(self, symbols: List[str]):
        """
        Start streaming mini ticker data for multiple symbols
        """
        self.running = True
        streams = [f"{self._symbol_to_stream(s)}@miniTicker" for s in symbols]
        stream_url = f"{self.ws_url}/{'/'.join(streams)}"

        # Use combined stream
        combined_url = f"wss://stream.binance.com:9443/stream?streams={'/'.join(streams)}"
        if self.testnet:
            combined_url = f"wss://testnet.binance.vision/stream?streams={'/'.join(streams)}"

        logger.info(f"Connecting to Binance WebSocket: {len(symbols)} symbols")

        while self.running:
            try:
                async with websockets.connect(combined_url) as ws:
                    logger.info("Connected to Binance WebSocket")

                    while self.running:
                        try:
                            msg = await asyncio.wait_for(ws.recv(), timeout=30)
                            data = json.loads(msg)

                            if "data" in data:
                                ticker_data = data["data"]
                                await self._process_ticker(ticker_data)

                        except asyncio.TimeoutError:
                            # Send ping to keep alive
                            await ws.ping()

            except Exception as e:
                logger.error(f"WebSocket error: {e}")
                if self.running:
                    logger.info("Reconnecting in 5 seconds...")
                    await asyncio.sleep(5)

    async def start_kline_stream(self, symbols: List[str], interval: str = "1m"):
        """
        Start streaming kline/candlestick data
        """
        self.running = True
        streams = [f"{self._symbol_to_stream(s)}@kline_{interval}" for s in symbols]

        combined_url = f"wss://stream.binance.com:9443/stream?streams={'/'.join(streams)}"
        if self.testnet:
            combined_url = f"wss://testnet.binance.vision/stream?streams={'/'.join(streams)}"

        logger.info(f"Starting kline stream for {len(symbols)} symbols")

        while self.running:
            try:
                async with websockets.connect(combined_url) as ws:
                    logger.info("Connected to Binance Kline WebSocket")

                    while self.running:
                        try:
                            msg = await asyncio.wait_for(ws.recv(), timeout=30)
                            data = json.loads(msg)

                            if "data" in data:
                                kline_data = data["data"]
                                await self._process_kline(kline_data)

                        except asyncio.TimeoutError:
                            await ws.ping()

            except Exception as e:
                logger.error(f"Kline WebSocket error: {e}")
                if self.running:
                    await asyncio.sleep(5)

    async def _process_ticker(self, data: dict):
        """Process mini ticker data and broadcast"""
        try:
            # Binance mini ticker format
            symbol = data.get("s", "")  # e.g., BTCUSDT

            # Convert back to our format
            if symbol.endswith("USDT"):
                our_symbol = symbol[:-4] + "/USDT"
            else:
                our_symbol = symbol

            ticker = {
                "type": "ticker",
                "symbol": our_symbol,
                "price": float(data.get("c", 0)),  # Close price
                "open": float(data.get("o", 0)),
                "high": float(data.get("h", 0)),
                "low": float(data.get("l", 0)),
                "volume": float(data.get("v", 0)),
                "quote_volume": float(data.get("q", 0)),
                "timestamp": datetime.utcnow().isoformat()
            }

            await self.manager.broadcast_to_symbol(our_symbol, ticker)

        except Exception as e:
            logger.error(f"Error processing ticker: {e}")

    async def _process_kline(self, data: dict):
        """Process kline data and broadcast"""
        try:
            kline = data.get("k", {})
            symbol = data.get("s", "")

            if symbol.endswith("USDT"):
                our_symbol = symbol[:-4] + "/USDT"
            else:
                our_symbol = symbol

            candle = {
                "type": "kline",
                "symbol": our_symbol,
                "interval": kline.get("i"),
                "open_time": kline.get("t"),
                "close_time": kline.get("T"),
                "open": float(kline.get("o", 0)),
                "high": float(kline.get("h", 0)),
                "low": float(kline.get("l", 0)),
                "close": float(kline.get("c", 0)),
                "volume": float(kline.get("v", 0)),
                "is_closed": kline.get("x", False),
                "timestamp": datetime.utcnow().isoformat()
            }

            await self.manager.broadcast_to_symbol(our_symbol, candle)

        except Exception as e:
            logger.error(f"Error processing kline: {e}")

    def stop(self):
        self.running = False


# Global instances
manager = ConnectionManager()
binance_ws = BinanceWebSocketClient(manager, testnet=True)
