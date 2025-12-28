"""
Push Notification Service
Sends notifications to Android devices via Firebase Cloud Messaging
"""

import os
import httpx
from loguru import logger
from typing import Optional, List
from supabase import create_client

# Supabase client
SUPABASE_URL = os.getenv("SUPABASE_URL", "")
SUPABASE_KEY = os.getenv("SUPABASE_SERVICE_KEY", os.getenv("SUPABASE_ANON_KEY", ""))

# Firebase Cloud Messaging
FCM_SERVER_KEY = os.getenv("FCM_SERVER_KEY", "")
FCM_URL = "https://fcm.googleapis.com/fcm/send"


class NotificationService:
    """Service for sending push notifications"""

    def __init__(self):
        self.supabase = None
        if SUPABASE_URL and SUPABASE_KEY:
            self.supabase = create_client(SUPABASE_URL, SUPABASE_KEY)

    async def get_active_tokens(self) -> List[str]:
        """Get all active FCM tokens from database"""
        if not self.supabase:
            return []

        try:
            result = self.supabase.table("fcm_tokens").select("token").eq("is_active", True).execute()
            return [row["token"] for row in result.data]
        except Exception as e:
            logger.error(f"Failed to get FCM tokens: {e}")
            return []

    async def send_notification(
        self,
        title: str,
        body: str,
        data: Optional[dict] = None,
        tokens: Optional[List[str]] = None
    ) -> bool:
        """
        Send push notification to all active devices or specific tokens
        """
        if not FCM_SERVER_KEY:
            logger.warning("FCM_SERVER_KEY not configured, skipping notification")
            return False

        # Get tokens if not provided
        if tokens is None:
            tokens = await self.get_active_tokens()

        if not tokens:
            logger.info("No active FCM tokens, skipping notification")
            return False

        headers = {
            "Authorization": f"key={FCM_SERVER_KEY}",
            "Content-Type": "application/json"
        }

        # Send to each token (or use topics for batch sending)
        success_count = 0
        failed_tokens = []

        async with httpx.AsyncClient() as client:
            for token in tokens:
                payload = {
                    "to": token,
                    "notification": {
                        "title": title,
                        "body": body,
                        "sound": "default"
                    },
                    "data": data or {},
                    "priority": "high"
                }

                try:
                    response = await client.post(FCM_URL, json=payload, headers=headers)
                    result = response.json()

                    if result.get("success") == 1:
                        success_count += 1
                    else:
                        # Token might be invalid
                        if result.get("results", [{}])[0].get("error") in [
                            "NotRegistered", "InvalidRegistration"
                        ]:
                            failed_tokens.append(token)
                        logger.warning(f"FCM send failed: {result}")

                except Exception as e:
                    logger.error(f"FCM request failed: {e}")

        # Deactivate invalid tokens
        if failed_tokens and self.supabase:
            try:
                for token in failed_tokens:
                    self.supabase.table("fcm_tokens").update(
                        {"is_active": False}
                    ).eq("token", token).execute()
                logger.info(f"Deactivated {len(failed_tokens)} invalid tokens")
            except Exception as e:
                logger.error(f"Failed to deactivate tokens: {e}")

        logger.info(f"Sent notifications: {success_count}/{len(tokens)} successful")
        return success_count > 0

    async def notify_trade_executed(
        self,
        action: str,
        coin: str,
        amount: float,
        price: float
    ):
        """Send notification when bot executes a trade"""
        emoji = "🟢" if action == "BUY" else "🔴"
        title = f"{emoji} {action} {coin}"
        body = f"${amount:.2f} @ ${price:.2f}"

        data = {
            "type": "TRADE_EXECUTED",
            "action": action,
            "coin": coin,
            "amount": str(amount),
            "price": str(price)
        }

        await self.send_notification(title, body, data)

    async def notify_profit_loss(
        self,
        coin: str,
        pnl: float,
        pnl_percent: float,
        reason: str
    ):
        """Send notification for take-profit or stop-loss"""
        emoji = "💰" if pnl >= 0 else "📉"
        sign = "+" if pnl >= 0 else ""
        title = f"{emoji} {coin} {reason.replace('_', ' ')}"
        body = f"{sign}${pnl:.2f} ({sign}{pnl_percent:.1f}%)"

        data = {
            "type": "PROFIT_LOSS",
            "coin": coin,
            "pnl": str(pnl),
            "pnl_percent": str(pnl_percent),
            "reason": reason
        }

        await self.send_notification(title, body, data)

    async def notify_strong_signal(
        self,
        coin: str,
        signal: str,
        score: int
    ):
        """Send notification for strong buy/sell signals"""
        emoji = "🚀" if "BUY" in signal else "⚠️"
        title = f"{emoji} {signal.replace('_', ' ')}: {coin}"
        body = f"Score: {score}/100"

        data = {
            "type": "STRONG_SIGNAL",
            "coin": coin,
            "signal": signal,
            "score": str(score)
        }

        await self.send_notification(title, body, data)


# Global instance
notification_service = NotificationService()
