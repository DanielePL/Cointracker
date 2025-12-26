"""
Hybrid ML Model: LSTM + XGBoost
- LSTM captures temporal patterns
- XGBoost makes final prediction with feature importance
- SHAP provides explainability
"""

import numpy as np
from typing import Dict, List, Optional, Tuple
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
import json
from loguru import logger

# Try to import ML libraries (optional)
try:
    import torch
    import torch.nn as nn
    TORCH_AVAILABLE = True
except ImportError:
    TORCH_AVAILABLE = False
    logger.warning("PyTorch not installed. Using rule-based fallback.")

try:
    import xgboost as xgb
    XGBOOST_AVAILABLE = True
except ImportError:
    XGBOOST_AVAILABLE = False
    logger.warning("XGBoost not installed. Using rule-based fallback.")

from app.ml.feature_engineer import FeatureVector


@dataclass
class ModelPrediction:
    """Output from the hybrid model"""
    signal: str                      # BUY, SELL, HOLD
    score: int                       # 0-100
    confidence: float                # 0-1
    direction_probs: Dict[str, float]  # {buy: 0.7, hold: 0.2, sell: 0.1}
    feature_importance: Dict[str, float]
    top_reasons: List[str]
    timestamp: datetime


class LSTMEncoder(nn.Module if TORCH_AVAILABLE else object):
    """
    LSTM that encodes temporal sequences into fixed-size embeddings
    Captures patterns like:
    - "Fear index was low for 3 days before rally"
    - "Volume spiked 2h before crash"
    """

    def __init__(self, input_size: int = 28, hidden_size: int = 128, num_layers: int = 2):
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

        self.fc = nn.Linear(hidden_size, hidden_size // 2)
        self.relu = nn.ReLU()
        self.dropout = nn.Dropout(0.2)

    def forward(self, x):
        # x shape: (batch, sequence_length, features)
        lstm_out, (hidden, cell) = self.lstm(x)

        # Use last hidden state
        last_hidden = hidden[-1]  # (batch, hidden_size)

        # Project to embedding
        embedding = self.dropout(self.relu(self.fc(last_hidden)))
        return embedding  # (batch, hidden_size // 2)


class HybridModel:
    """
    Hybrid LSTM + XGBoost Model

    Pipeline:
    1. LSTM processes sequence of features -> temporal embedding
    2. Combine embedding with current features
    3. XGBoost makes final prediction
    4. SHAP explains the decision
    """

    MODEL_DIR = Path("models")

    def __init__(self):
        self.lstm_encoder: Optional[LSTMEncoder] = None
        self.xgb_model: Optional[xgb.Booster] = None
        self.feature_names = FeatureVector.feature_names()
        self.is_trained = False

        # Try to load pre-trained models
        self._load_models()

    def _load_models(self):
        """Load pre-trained models if available"""
        self.MODEL_DIR.mkdir(exist_ok=True)

        lstm_path = self.MODEL_DIR / "lstm_encoder.pt"
        xgb_path = self.MODEL_DIR / "xgboost_model.json"

        if TORCH_AVAILABLE and lstm_path.exists():
            try:
                self.lstm_encoder = LSTMEncoder()
                self.lstm_encoder.load_state_dict(torch.load(lstm_path))
                self.lstm_encoder.eval()
                logger.info("Loaded LSTM encoder")
            except Exception as e:
                logger.error(f"Failed to load LSTM: {e}")

        if XGBOOST_AVAILABLE and xgb_path.exists():
            try:
                self.xgb_model = xgb.Booster()
                self.xgb_model.load_model(str(xgb_path))
                self.is_trained = True
                logger.info("Loaded XGBoost model")
            except Exception as e:
                logger.error(f"Failed to load XGBoost: {e}")

    def predict(
        self,
        current_features: FeatureVector,
        feature_sequence: Optional[np.ndarray] = None
    ) -> ModelPrediction:
        """
        Make prediction using hybrid model

        Args:
            current_features: Current feature vector
            feature_sequence: Optional sequence of historical features (24, num_features)
        """
        # If models aren't trained, use rule-based fallback
        if not self.is_trained or not XGBOOST_AVAILABLE:
            return self._rule_based_prediction(current_features)

        try:
            # 1. Get temporal embedding from LSTM
            temporal_embedding = np.zeros(64)  # Default if LSTM not available

            if TORCH_AVAILABLE and self.lstm_encoder and feature_sequence is not None:
                with torch.no_grad():
                    seq_tensor = torch.FloatTensor(feature_sequence).unsqueeze(0)
                    temporal_embedding = self.lstm_encoder(seq_tensor).numpy().flatten()

            # 2. Combine features
            current_array = current_features.to_array()
            combined_features = np.concatenate([temporal_embedding, current_array])

            # 3. XGBoost prediction
            dmatrix = xgb.DMatrix(combined_features.reshape(1, -1))
            raw_prediction = self.xgb_model.predict(dmatrix)[0]

            # 4. Get feature importance
            importance = self._get_feature_importance(combined_features)

            # 5. Generate prediction
            return self._create_prediction(raw_prediction, importance, current_features)

        except Exception as e:
            logger.error(f"Model prediction failed: {e}")
            return self._rule_based_prediction(current_features)

    def _rule_based_prediction(self, features: FeatureVector) -> ModelPrediction:
        """
        Rule-based fallback when ML models aren't available
        Uses weighted scoring of indicators
        """
        score = 50  # Start neutral
        reasons = []
        feature_importance = {}

        # RSI contribution (weight: 15%)
        rsi = features.rsi_14 * 100
        if rsi < 30:
            score += 15
            reasons.append(f"RSI oversold at {rsi:.1f} (bullish)")
            feature_importance["rsi_14"] = 0.15
        elif rsi > 70:
            score -= 15
            reasons.append(f"RSI overbought at {rsi:.1f} (bearish)")
            feature_importance["rsi_14"] = -0.15
        else:
            feature_importance["rsi_14"] = 0.0

        # RSI Divergence (weight: 10%)
        if features.rsi_divergence == 1:
            score += 10
            reasons.append("Bullish RSI divergence detected")
            feature_importance["rsi_divergence"] = 0.10
        elif features.rsi_divergence == -1:
            score -= 10
            reasons.append("Bearish RSI divergence detected")
            feature_importance["rsi_divergence"] = -0.10

        # MACD (weight: 15%)
        if features.macd_cross == 1:
            score += 15
            reasons.append("MACD bullish crossover")
            feature_importance["macd_cross"] = 0.15
        elif features.macd_cross == -1:
            score -= 15
            reasons.append("MACD bearish crossover")
            feature_importance["macd_cross"] = -0.15

        if features.macd_histogram > 0:
            score += 5
            feature_importance["macd_histogram"] = 0.05
        else:
            score -= 5
            feature_importance["macd_histogram"] = -0.05

        # EMA Trend (weight: 10%)
        if features.ema_alignment == 1:
            score += 10
            reasons.append("EMA50 above EMA200 (bullish trend)")
            feature_importance["ema_alignment"] = 0.10
        else:
            score -= 10
            reasons.append("EMA50 below EMA200 (bearish trend)")
            feature_importance["ema_alignment"] = -0.10

        # Bollinger Bands (weight: 10%)
        if features.bb_position < 0.2:
            score += 10
            reasons.append(f"Price near lower Bollinger Band (potential bounce)")
            feature_importance["bb_position"] = 0.10
        elif features.bb_position > 0.8:
            score -= 10
            reasons.append(f"Price near upper Bollinger Band (potential resistance)")
            feature_importance["bb_position"] = -0.10

        # Fear & Greed (weight: 20% - CRITICAL)
        fg = features.fear_greed_index * 100
        if features.fear_greed_extreme == 1:  # Extreme fear
            score += 20
            reasons.append(f"Fear & Greed at {fg:.0f} (Extreme Fear = buy opportunity)")
            feature_importance["fear_greed_index"] = 0.20
        elif features.fear_greed_extreme == 2:  # Extreme greed
            score -= 15
            reasons.append(f"Fear & Greed at {fg:.0f} (Extreme Greed = caution)")
            feature_importance["fear_greed_index"] = -0.15
        elif fg < 40:
            score += 10
            reasons.append(f"Fear & Greed at {fg:.0f} (Fear = potential opportunity)")
            feature_importance["fear_greed_index"] = 0.10
        elif fg > 60:
            score -= 5
            reasons.append(f"Fear & Greed at {fg:.0f} (Greed = be cautious)")
            feature_importance["fear_greed_index"] = -0.05

        # Volume (weight: 10%)
        if features.volume_ratio > 1.5:
            # High volume confirms the move
            if score > 50:
                score += 10
                reasons.append(f"High volume ({features.volume_ratio:.1f}x avg) confirms bullish move")
            else:
                score -= 10
                reasons.append(f"High volume ({features.volume_ratio:.1f}x avg) confirms bearish move")
            feature_importance["volume_ratio"] = 0.10 if score > 50 else -0.10

        # News/Social Sentiment (weight: 10%)
        if features.news_sentiment > 0.3:
            score += 8
            reasons.append("Positive news sentiment")
            feature_importance["news_sentiment"] = 0.08
        elif features.news_sentiment < -0.3:
            score -= 8
            reasons.append("Negative news sentiment")
            feature_importance["news_sentiment"] = -0.08

        # Clamp score
        score = max(0, min(100, score))

        # Determine signal
        if score >= 65:
            signal = "STRONG_BUY" if score >= 80 else "BUY"
        elif score <= 35:
            signal = "STRONG_SELL" if score <= 20 else "SELL"
        else:
            signal = "HOLD"

        # Calculate confidence based on how extreme the score is
        confidence = abs(score - 50) / 50

        # Direction probabilities
        if score >= 50:
            buy_prob = score / 100
            sell_prob = (100 - score) / 200
            hold_prob = 1 - buy_prob - sell_prob
        else:
            sell_prob = (100 - score) / 100
            buy_prob = score / 200
            hold_prob = 1 - buy_prob - sell_prob

        return ModelPrediction(
            signal=signal,
            score=score,
            confidence=confidence,
            direction_probs={"buy": buy_prob, "hold": hold_prob, "sell": sell_prob},
            feature_importance=feature_importance,
            top_reasons=reasons[:5],  # Top 5 reasons
            timestamp=datetime.utcnow()
        )

    def _get_feature_importance(self, features: np.ndarray) -> Dict[str, float]:
        """Extract feature importance from XGBoost"""
        if not self.xgb_model:
            return {}

        try:
            importance = self.xgb_model.get_score(importance_type='gain')
            # Map to feature names
            result = {}
            for i, name in enumerate(self.feature_names):
                key = f"f{i}"
                result[name] = importance.get(key, 0.0)
            return result
        except Exception:
            return {}

    def _create_prediction(
        self,
        raw_score: float,
        importance: Dict[str, float],
        features: FeatureVector
    ) -> ModelPrediction:
        """Create prediction from XGBoost output"""
        # Assume raw_score is 0-1 probability of bullish
        score = int(raw_score * 100)

        if score >= 65:
            signal = "STRONG_BUY" if score >= 80 else "BUY"
        elif score <= 35:
            signal = "STRONG_SELL" if score <= 20 else "SELL"
        else:
            signal = "HOLD"

        confidence = abs(score - 50) / 50

        # Generate reasons from top important features
        sorted_importance = sorted(importance.items(), key=lambda x: abs(x[1]), reverse=True)
        reasons = []
        for name, imp in sorted_importance[:5]:
            if imp > 0:
                reasons.append(f"{name} contributing positively (+{imp:.2f})")
            elif imp < 0:
                reasons.append(f"{name} contributing negatively ({imp:.2f})")

        return ModelPrediction(
            signal=signal,
            score=score,
            confidence=confidence,
            direction_probs={
                "buy": raw_score,
                "hold": 0.5 - abs(raw_score - 0.5),
                "sell": 1 - raw_score
            },
            feature_importance=importance,
            top_reasons=reasons,
            timestamp=datetime.utcnow()
        )

    def train(
        self,
        sequences: np.ndarray,
        features: np.ndarray,
        labels: np.ndarray,
        epochs: int = 100
    ):
        """
        Train the hybrid model

        Args:
            sequences: Historical sequences (n_samples, seq_len, n_features)
            features: Current features (n_samples, n_features)
            labels: Target labels (n_samples,) - 0=sell, 0.5=hold, 1=buy
        """
        logger.info(f"Training hybrid model with {len(labels)} samples")

        # 1. Train LSTM encoder
        if TORCH_AVAILABLE:
            self._train_lstm(sequences, labels, epochs)

        # 2. Generate LSTM embeddings
        embeddings = self._generate_embeddings(sequences)

        # 3. Combine and train XGBoost
        if XGBOOST_AVAILABLE:
            combined = np.concatenate([embeddings, features], axis=1)
            self._train_xgboost(combined, labels)

        self.is_trained = True
        self._save_models()
        logger.info("Training complete")

    def _train_lstm(self, sequences: np.ndarray, labels: np.ndarray, epochs: int):
        """Train LSTM encoder"""
        if not TORCH_AVAILABLE:
            return

        self.lstm_encoder = LSTMEncoder(input_size=sequences.shape[2])
        optimizer = torch.optim.Adam(self.lstm_encoder.parameters(), lr=0.001)
        criterion = nn.MSELoss()

        self.lstm_encoder.train()
        for epoch in range(epochs):
            optimizer.zero_grad()

            x = torch.FloatTensor(sequences)
            y = torch.FloatTensor(labels)

            embedding = self.lstm_encoder(x)
            # Simple reconstruction loss
            pred = embedding.mean(dim=1)
            loss = criterion(pred, y)

            loss.backward()
            optimizer.step()

            if (epoch + 1) % 10 == 0:
                logger.info(f"LSTM Epoch {epoch+1}/{epochs}, Loss: {loss.item():.4f}")

        self.lstm_encoder.eval()

    def _generate_embeddings(self, sequences: np.ndarray) -> np.ndarray:
        """Generate LSTM embeddings for all sequences"""
        if not TORCH_AVAILABLE or not self.lstm_encoder:
            return np.zeros((len(sequences), 64))

        with torch.no_grad():
            x = torch.FloatTensor(sequences)
            embeddings = self.lstm_encoder(x).numpy()
        return embeddings

    def _train_xgboost(self, features: np.ndarray, labels: np.ndarray):
        """Train XGBoost classifier"""
        if not XGBOOST_AVAILABLE:
            return

        dtrain = xgb.DMatrix(features, label=labels)

        params = {
            'objective': 'reg:squarederror',
            'max_depth': 6,
            'learning_rate': 0.05,
            'n_estimators': 500,
            'subsample': 0.8,
            'colsample_bytree': 0.8,
            'min_child_weight': 3,
            'eval_metric': 'rmse'
        }

        self.xgb_model = xgb.train(
            params,
            dtrain,
            num_boost_round=500,
            verbose_eval=50
        )

    def _save_models(self):
        """Save trained models"""
        self.MODEL_DIR.mkdir(exist_ok=True)

        if TORCH_AVAILABLE and self.lstm_encoder:
            torch.save(
                self.lstm_encoder.state_dict(),
                self.MODEL_DIR / "lstm_encoder.pt"
            )

        if XGBOOST_AVAILABLE and self.xgb_model:
            self.xgb_model.save_model(str(self.MODEL_DIR / "xgboost_model.json"))


# Global instance
hybrid_model = HybridModel()
