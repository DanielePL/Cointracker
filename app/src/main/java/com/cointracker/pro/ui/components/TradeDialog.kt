package com.cointracker.pro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cointracker.pro.ui.theme.*

/**
 * Trade Dialog for Paper Trading
 * Supports BUY and SELL operations
 */
@Composable
fun TradeDialog(
    symbol: String,
    currentPrice: Double,
    availableBalance: Double,
    availableQuantity: Double,
    isBuy: Boolean,
    isLoading: Boolean = false,
    onConfirm: (amount: Double, quantity: Double) -> Unit,
    onDismiss: () -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    val amount = amountText.toDoubleOrNull() ?: 0.0

    // For BUY: amount is in USDT, calculate quantity
    // For SELL: amount is in crypto units
    val quantity = if (isBuy) {
        if (currentPrice > 0) amount / currentPrice else 0.0
    } else {
        amount
    }

    val totalValue = if (isBuy) amount else quantity * currentPrice

    val isValid = if (isBuy) {
        amount > 0 && amount <= availableBalance
    } else {
        quantity > 0 && quantity <= availableQuantity
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            DeepBlue,
                            DeepBlue.copy(alpha = 0.95f)
                        )
                    )
                )
                .border(
                    1.dp,
                    Color.White.copy(alpha = 0.1f),
                    RoundedCornerShape(24.dp)
                )
                .padding(24.dp)
        ) {
            Column {
                // Header
                Text(
                    text = if (isBuy) "BUY" else "SELL",
                    color = if (isBuy) BullishGreen else BearishRed,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = symbol,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Current Price
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Current Price",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp
                    )
                    Text(
                        text = "$${formatPrice(currentPrice)}",
                        color = ElectricBlue,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Available Balance/Quantity
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isBuy) "Available" else "Holdings",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (isBuy) {
                                "$${formatPrice(availableBalance)} USDT"
                            } else {
                                "${formatQuantity(availableQuantity)} ${symbol.replace("USDT", "")}"
                            },
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Amount Input
                Text(
                    text = if (isBuy) "Amount (USDT)" else "Quantity",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = if (isBuy) "0.00" else "0.00000000",
                            color = Color.White.copy(alpha = 0.3f)
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = if (isBuy) BullishGreen else BearishRed,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        cursorColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Quick Amount Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val percentages = listOf(25, 50, 75, 100)
                    percentages.forEach { pct ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.1f))
                                .clickable {
                                    val maxAmount = if (isBuy) availableBalance else availableQuantity
                                    amountText = formatForInput(maxAmount * pct / 100, !isBuy)
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$pct%",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Summary
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isBuy) BullishGreen.copy(alpha = 0.1f)
                            else BearishRed.copy(alpha = 0.1f)
                        )
                        .padding(12.dp)
                ) {
                    Column {
                        if (isBuy) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "You will receive",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "${formatQuantity(quantity)} ${symbol.replace("USDT", "")}",
                                    color = BullishGreen,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "You will receive",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "$${formatPrice(totalValue)} USDT",
                                    color = BearishRed,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Cancel
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White
                        ),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.3f),
                                    Color.White.copy(alpha = 0.3f)
                                )
                            )
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }

                    // Confirm
                    Button(
                        onClick = { onConfirm(amount, quantity) },
                        modifier = Modifier.weight(1f),
                        enabled = isValid && !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isBuy) BullishGreen else BearishRed,
                            contentColor = Color.White,
                            disabledContainerColor = Color.Gray.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = if (isBuy) "Confirm BUY" else "Confirm SELL",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Paper Trading Notice
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Paper Trading - No real money involved",
                    color = AccentOrange.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun formatPrice(price: Double): String {
    return when {
        price >= 1000 -> String.format("%,.2f", price)
        price >= 1 -> String.format("%.2f", price)
        price >= 0.01 -> String.format("%.4f", price)
        else -> String.format("%.8f", price)
    }
}

private fun formatQuantity(qty: Double): String {
    return when {
        qty >= 1000 -> String.format("%,.2f", qty)
        qty >= 1 -> String.format("%.4f", qty)
        else -> String.format("%.8f", qty)
    }
}

private fun formatForInput(value: Double, isCrypto: Boolean): String {
    return if (isCrypto) {
        String.format("%.8f", value).trimEnd('0').trimEnd('.')
    } else {
        String.format("%.2f", value)
    }
}
