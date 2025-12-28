"""
ML Model Trainer
Trains LSTM + XGBoost models on labeled data from Supabase
"""

import os
import json
import asyncio
from pathlib import Path
from datetime import datetime
from typing import Dict, List, Optional, Tuple
from dataclasses import dataclass
from loguru import logger
import numpy as np

# Supabase
try:
    from supabase import create_client, Client
    SUPABASE_URL = os.getenv("SUPABASE_URL", "")
    SUPABASE_KEY = os.getenv("SUPABASE_SERVICE_KEY", "")
    supabase: Optional[Client] = create_client(SUPABASE_URL, SUPABASE_KEY) if SUPABASE_KEY else None
except ImportError:
    supabase = None

# ML Libraries (optional)
try:
    import torch
    import torch.nn as nn
    from torch.utils.data import DataLoader, TensorDataset
    TORCH_AVAILABLE = True
except ImportError:
    TORCH_AVAILABLE = False
    logger.warning("PyTorch not available - LSTM training disabled")

try:
    import xgboost as xgb
    XGBOOST_AVAILABLE = True
except ImportError:
    XGBOOST_AVAILABLE = False
    logger.warning("XGBoost not available - XGBoost training disabled")


MODEL_DIR = Path("models")


@dataclass
class TrainingStats:
    """Training statistics"""
    total_samples: int
    train_samples: int
    test_samples: int
    epochs: int
    final_loss: float
    accuracy: float
    timestamp: datetime


class LSTMModel(nn.Module if TORCH_AVAILABLE else object):
    """LSTM for sequence learning"""

    def __init__(self, input_size: int = 10, hidden_size: int = 64, num_layers: int = 2):
        if not TORCH_AVAILABLE:
            return
        super().__init__()
        self.hidden_size = hidden_size
        self.num_layers = num_layers

        self.lstm = nn.LSTM(
            input_size=input_size,
            hidden_size=hidden_size,
            num_layers=num_layers,
            batch_first=True,
            dropout=0.2 if num_layers > 1 else 0
        )
        self.fc1 = nn.Linear(hidden_size, 32)
        self.fc2 = nn.Linear(32, 1)
        self.relu = nn.ReLU()
        self.sigmoid = nn.Sigmoid()

    def forward(self, x):
        lstm_out, _ = self.lstm(x)
        last_output = lstm_out[:, -1, :]
        x = self.relu(self.fc1(last_output))
        x = self.sigmoid(self.fc2(x))
        return x


class MLTrainer:
    """Handles ML model training"""

    def __init__(self):
        self.supabase = supabase
        MODEL_DIR.mkdir(exist_ok=True)

    async def trigger_labeling(self) -> Dict:
        """Trigger auto-labeling of training data in Supabase"""
        if not self.supabase:
            return {"error": "Supabase not available"}

        try:
            result = self.supabase.rpc("auto_label_training_data").execute()
            logger.info(f"Labeling result: {result.data}")
            return result.data if result.data else {"labeled": 0, "skipped": 0}
        except Exception as e:
            logger.error(f"Labeling failed: {e}")
            return {"error": str(e)}

    async def get_training_stats(self) -> Dict:
        """Get current training data statistics"""
        if not self.supabase:
            return {"error": "Supabase not available"}

        try:
            result = self.supabase.table("ml_training_stats").select("*").execute()
            if result.data:
                return result.data[0]
            return {"total_samples": 0, "labeled_samples": 0}
        except Exception as e:
            logger.error(f"Failed to get stats: {e}")
            return {"error": str(e)}

    async def fetch_training_data(self, limit: int = 10000) -> Tuple[np.ndarray, np.ndarray]:
        """Fetch training data from Supabase"""
        if not self.supabase:
            raise Exception("Supabase not available")

        result = self.supabase.rpc("export_training_data", {"p_limit": limit}).execute()

        if not result.data:
            raise Exception("No training data available")

        features_list = []
        labels_list = []

        for row in result.data:
            features = row.get("features", {})
            label = row.get("label_numeric", 0.5)

            # Extract features in consistent order
            feature_vector = [
                features.get("rsi", 50) / 100,  # Normalize RSI to 0-1
                features.get("macd", 0),
                features.get("macd_signal", 0),
                features.get("ema_12", 0),
                features.get("ema_26", 0),
                features.get("bb_position", 0.5),
                features.get("volume_24h", 0) / 1e9,  # Normalize volume
                features.get("price_change_24h", 0) / 100,  # Normalize to -1 to 1
            ]

            features_list.append(feature_vector)
            labels_list.append(label)

        X = np.array(features_list, dtype=np.float32)
        y = np.array(labels_list, dtype=np.float32)

        logger.info(f"Fetched {len(X)} training samples")
        return X, y

    def train_xgboost(self, X: np.ndarray, y: np.ndarray) -> Dict:
        """Train XGBoost model"""
        if not XGBOOST_AVAILABLE:
            return {"error": "XGBoost not available"}

        # Split data
        split_idx = int(len(X) * 0.8)
        X_train, X_test = X[:split_idx], X[split_idx:]
        y_train, y_test = y[:split_idx], y[split_idx:]

        dtrain = xgb.DMatrix(X_train, label=y_train)
        dtest = xgb.DMatrix(X_test, label=y_test)

        params = {
            'objective': 'reg:squarederror',
            'max_depth': 6,
            'learning_rate': 0.05,
            'subsample': 0.8,
            'colsample_bytree': 0.8,
            'min_child_weight': 3,
            'eval_metric': 'rmse'
        }

        evals = [(dtrain, 'train'), (dtest, 'test')]

        logger.info("Training XGBoost model...")
        model = xgb.train(
            params,
            dtrain,
            num_boost_round=500,
            evals=evals,
            early_stopping_rounds=50,
            verbose_eval=50
        )

        # Save model
        model_path = MODEL_DIR / "xgboost_model.json"
        model.save_model(str(model_path))
        logger.info(f"XGBoost model saved to {model_path}")

        # Evaluate
        predictions = model.predict(dtest)
        accuracy = self._calculate_accuracy(predictions, y_test)

        return {
            "model": "xgboost",
            "train_samples": len(X_train),
            "test_samples": len(X_test),
            "best_iteration": model.best_iteration,
            "accuracy": accuracy,
            "model_path": str(model_path)
        }

    def train_lstm(self, X: np.ndarray, y: np.ndarray, epochs: int = 100) -> Dict:
        """Train LSTM model"""
        if not TORCH_AVAILABLE:
            return {"error": "PyTorch not available"}

        # Create sequences (use sliding window)
        seq_length = 24  # 24 samples per sequence
        X_seq, y_seq = self._create_sequences(X, y, seq_length)

        if len(X_seq) < 100:
            return {"error": f"Not enough sequential data (need 100+, have {len(X_seq)})"}

        # Split
        split_idx = int(len(X_seq) * 0.8)
        X_train = torch.FloatTensor(X_seq[:split_idx])
        y_train = torch.FloatTensor(y_seq[:split_idx]).unsqueeze(1)
        X_test = torch.FloatTensor(X_seq[split_idx:])
        y_test = torch.FloatTensor(y_seq[split_idx:]).unsqueeze(1)

        # Create DataLoader
        train_dataset = TensorDataset(X_train, y_train)
        train_loader = DataLoader(train_dataset, batch_size=32, shuffle=True)

        # Model
        model = LSTMModel(input_size=X.shape[1])
        criterion = nn.MSELoss()
        optimizer = torch.optim.Adam(model.parameters(), lr=0.001)

        logger.info("Training LSTM model...")
        best_loss = float('inf')

        for epoch in range(epochs):
            model.train()
            total_loss = 0

            for batch_X, batch_y in train_loader:
                optimizer.zero_grad()
                outputs = model(batch_X)
                loss = criterion(outputs, batch_y)
                loss.backward()
                optimizer.step()
                total_loss += loss.item()

            avg_loss = total_loss / len(train_loader)

            if (epoch + 1) % 20 == 0:
                logger.info(f"Epoch {epoch+1}/{epochs}, Loss: {avg_loss:.4f}")

            if avg_loss < best_loss:
                best_loss = avg_loss
                torch.save(model.state_dict(), MODEL_DIR / "lstm_encoder.pt")

        # Evaluate
        model.eval()
        with torch.no_grad():
            predictions = model(X_test).numpy().flatten()
            accuracy = self._calculate_accuracy(predictions, y_test.numpy().flatten())

        logger.info(f"LSTM model saved to {MODEL_DIR / 'lstm_encoder.pt'}")

        return {
            "model": "lstm",
            "train_samples": len(X_train),
            "test_samples": len(X_test),
            "epochs": epochs,
            "final_loss": best_loss,
            "accuracy": accuracy,
            "model_path": str(MODEL_DIR / "lstm_encoder.pt")
        }

    def _create_sequences(self, X: np.ndarray, y: np.ndarray, seq_length: int) -> Tuple[np.ndarray, np.ndarray]:
        """Create sequences for LSTM training"""
        X_seq = []
        y_seq = []

        for i in range(len(X) - seq_length):
            X_seq.append(X[i:i + seq_length])
            y_seq.append(y[i + seq_length])

        return np.array(X_seq), np.array(y_seq)

    def _calculate_accuracy(self, predictions: np.ndarray, labels: np.ndarray, threshold: float = 0.1) -> float:
        """Calculate directional accuracy"""
        # Convert to direction (>0.5 = buy, <0.5 = sell)
        pred_direction = predictions > 0.5
        label_direction = labels > 0.5
        correct = np.sum(pred_direction == label_direction)
        return correct / len(labels) * 100

    async def train_all(self, min_samples: int = 500) -> Dict:
        """Train all models"""
        # First, trigger labeling
        label_result = await self.trigger_labeling()
        logger.info(f"Labeling: {label_result}")

        # Check stats
        stats = await self.get_training_stats()
        labeled_samples = stats.get("labeled_samples", 0)

        if labeled_samples < min_samples:
            return {
                "error": f"Not enough labeled samples (have {labeled_samples}, need {min_samples})",
                "stats": stats,
                "recommendation": f"Wait ~{(min_samples - labeled_samples) // 600} more hours of data collection"
            }

        # Fetch data
        try:
            X, y = await self.fetch_training_data(limit=min(labeled_samples, 50000))
        except Exception as e:
            return {"error": str(e)}

        results = {
            "timestamp": datetime.utcnow().isoformat(),
            "total_samples": len(X),
            "stats": stats
        }

        # Train XGBoost
        if XGBOOST_AVAILABLE:
            results["xgboost"] = self.train_xgboost(X, y)
        else:
            results["xgboost"] = {"status": "skipped", "reason": "XGBoost not installed"}

        # Train LSTM
        if TORCH_AVAILABLE:
            results["lstm"] = self.train_lstm(X, y)
        else:
            results["lstm"] = {"status": "skipped", "reason": "PyTorch not installed"}

        # Log to Supabase
        if self.supabase:
            try:
                self.supabase.table("bot_learning_log").insert({
                    "lesson_type": "MODEL_TRAINING",
                    "lesson_learned": f"Trained models on {len(X)} samples",
                    "indicators_snapshot": results
                }).execute()
            except Exception as e:
                logger.warning(f"Failed to log training: {e}")

        return results


# Global trainer instance
ml_trainer = MLTrainer()


# CLI interface
if __name__ == "__main__":
    import asyncio

    async def main():
        print("=" * 50)
        print("CoinTracker ML Trainer")
        print("=" * 50)

        # Get stats
        stats = await ml_trainer.get_training_stats()
        print(f"\nTraining Data Stats:")
        print(f"  Total samples: {stats.get('total_samples', 0)}")
        print(f"  Labeled samples: {stats.get('labeled_samples', 0)}")
        print(f"  Days of data: {stats.get('days_of_data', 0)}")

        labeled = stats.get('labeled_samples', 0)
        if labeled < 500:
            print(f"\n⚠️  Not enough data yet (need 500+, have {labeled})")
            print(f"   Keep collecting for ~{(500 - labeled) // 600} more hours")

            # Trigger labeling anyway
            print("\nTriggering labeling...")
            result = await ml_trainer.trigger_labeling()
            print(f"  Labeled: {result.get('labeled', 0)}")
            print(f"  Skipped: {result.get('skipped', 0)}")
        else:
            print(f"\n✅ Enough data for training!")
            print("\nStarting training...")
            result = await ml_trainer.train_all()
            print(f"\nTraining result:")
            print(json.dumps(result, indent=2, default=str))

    asyncio.run(main())
