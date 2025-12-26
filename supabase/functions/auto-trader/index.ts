// Supabase Edge Function: Auto Trader
// Runs every 5 minutes via pg_cron to execute paper trades based on signals

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

// Trading Configuration
const CONFIG = {
  MIN_SIGNAL_SCORE: 70,
  TRADE_PERCENTAGE: 0.10, // 10% of balance per trade
  MAX_POSITIONS: 3,
  STOP_LOSS_PERCENT: -5,
  TAKE_PROFIT_PERCENT: 10,
  SYMBOLS: ['BTCUSDT', 'ETHUSDT', 'SOLUSDT', 'XRPUSDT', 'ADAUSDT'],
}

// Types
interface TradingSignal {
  symbol: string
  signal: 'STRONG_BUY' | 'BUY' | 'HOLD' | 'SELL' | 'STRONG_SELL'
  score: number
  price: number
  stopLoss: number
  takeProfit: number
}

interface OHLCV {
  open: number
  high: number
  low: number
  close: number
  volume: number
}

serve(async (req) => {
  // Handle CORS
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders })
  }

  try {
    // Create Supabase client
    const supabaseUrl = Deno.env.get('SUPABASE_URL')!
    const supabaseKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!
    const supabase = createClient(supabaseUrl, supabaseKey)

    console.log('🤖 Auto Trader starting...')

    // 1. Get all users with auto-trading enabled
    const { data: activeUsers, error: usersError } = await supabase
      .from('auto_trading_settings')
      .select('user_id')
      .eq('enabled', true)

    if (usersError) throw usersError

    if (!activeUsers || activeUsers.length === 0) {
      console.log('No users with auto-trading enabled')
      return new Response(JSON.stringify({ message: 'No active users' }), {
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      })
    }

    console.log(`Found ${activeUsers.length} users with auto-trading enabled`)

    // 2. Fetch current prices and generate signals
    const signals = await generateSignals()
    console.log(`Generated ${signals.length} signals`)

    // 3. Process each user
    const results = []
    for (const user of activeUsers) {
      try {
        const result = await processUser(supabase, user.user_id, signals)
        results.push({ userId: user.user_id, ...result })
      } catch (error) {
        console.error(`Error processing user ${user.user_id}:`, error)
        results.push({ userId: user.user_id, error: error.message })
      }
    }

    // 4. Log execution
    await supabase.from('auto_trading_log').insert({
      executed_at: new Date().toISOString(),
      users_processed: activeUsers.length,
      signals: signals,
      results: results,
    })

    return new Response(JSON.stringify({
      success: true,
      usersProcessed: activeUsers.length,
      results
    }), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
    })

  } catch (error) {
    console.error('Auto Trader error:', error)
    return new Response(JSON.stringify({ error: error.message }), {
      status: 500,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
    })
  }
})

async function processUser(supabase: any, userId: string, signals: TradingSignal[]) {
  const trades: string[] = []

  // Get user's balance
  const { data: balance } = await supabase
    .from('paper_balance')
    .select('*')
    .eq('user_id', userId)
    .single()

  if (!balance) {
    return { trades: [], message: 'No balance found' }
  }

  // Get user's holdings
  const { data: holdings } = await supabase
    .from('paper_holdings')
    .select('*')
    .eq('user_id', userId)

  const currentHoldings = holdings || []

  // Check existing positions for stop-loss / take-profit
  for (const holding of currentHoldings) {
    const signal = signals.find(s => s.symbol === holding.symbol.replace('/', ''))
    if (signal) {
      const pnlPercent = ((signal.price - holding.avg_entry_price) / holding.avg_entry_price) * 100

      // Stop-Loss
      if (pnlPercent <= CONFIG.STOP_LOSS_PERCENT) {
        await executeSell(supabase, userId, holding.symbol, holding.quantity, signal.price, 'STOP_LOSS')
        trades.push(`STOP_LOSS: ${holding.symbol} @ ${signal.price}`)
      }
      // Take-Profit
      else if (pnlPercent >= CONFIG.TAKE_PROFIT_PERCENT) {
        await executeSell(supabase, userId, holding.symbol, holding.quantity, signal.price, 'TAKE_PROFIT')
        trades.push(`TAKE_PROFIT: ${holding.symbol} @ ${signal.price}`)
      }
    }
  }

  // Process strong signals
  const strongBuys = signals.filter(s => s.signal === 'STRONG_BUY' && s.score >= CONFIG.MIN_SIGNAL_SCORE)
  const strongSells = signals.filter(s => s.signal === 'STRONG_SELL' && s.score >= CONFIG.MIN_SIGNAL_SCORE)

  // Execute sells first
  for (const signal of strongSells) {
    const displaySymbol = formatSymbol(signal.symbol)
    const holding = currentHoldings.find((h: any) => h.symbol === displaySymbol)
    if (holding && holding.quantity > 0) {
      await executeSell(supabase, userId, displaySymbol, holding.quantity, signal.price, 'SIGNAL')
      trades.push(`SELL: ${displaySymbol} @ ${signal.price}`)
    }
  }

  // Execute buys
  const updatedHoldings = currentHoldings.filter((h: any) =>
    !strongSells.some(s => formatSymbol(s.symbol) === h.symbol)
  )

  for (const signal of strongBuys) {
    const displaySymbol = formatSymbol(signal.symbol)
    const hasPosition = updatedHoldings.some((h: any) => h.symbol === displaySymbol)

    if (!hasPosition && updatedHoldings.length < CONFIG.MAX_POSITIONS) {
      const tradeAmount = balance.balance_usdt * CONFIG.TRADE_PERCENTAGE
      if (tradeAmount >= 10) {
        const quantity = tradeAmount / signal.price
        await executeBuy(supabase, userId, displaySymbol, quantity, signal.price)
        trades.push(`BUY: ${displaySymbol} ${quantity.toFixed(6)} @ ${signal.price}`)
        updatedHoldings.push({ symbol: displaySymbol }) // Track for max positions
      }
    }
  }

  return { trades, balance: balance.balance_usdt }
}

async function executeBuy(supabase: any, userId: string, symbol: string, quantity: number, price: number) {
  const totalValue = quantity * price

  await supabase.from('paper_trades').insert({
    user_id: userId,
    symbol: symbol,
    side: 'BUY',
    quantity: quantity,
    entry_price: price,
    total_value: totalValue,
    status: 'OPEN',
    source: 'AUTO_TRADER'
  })

  console.log(`BUY executed: ${quantity} ${symbol} @ ${price}`)
}

async function executeSell(supabase: any, userId: string, symbol: string, quantity: number, price: number, reason: string) {
  // Get holding for avg entry price
  const { data: holding } = await supabase
    .from('paper_holdings')
    .select('avg_entry_price')
    .eq('user_id', userId)
    .eq('symbol', symbol)
    .single()

  const avgEntryPrice = holding?.avg_entry_price || price
  const totalValue = quantity * price
  const costBasis = quantity * avgEntryPrice
  const pnl = totalValue - costBasis
  const pnlPercent = costBasis > 0 ? (pnl / costBasis) * 100 : 0

  await supabase.from('paper_trades').insert({
    user_id: userId,
    symbol: symbol,
    side: 'SELL',
    quantity: quantity,
    entry_price: avgEntryPrice,
    exit_price: price,
    total_value: totalValue,
    pnl: pnl,
    pnl_percent: pnlPercent,
    status: 'CLOSED',
    source: 'AUTO_TRADER',
    close_reason: reason
  })

  console.log(`SELL executed: ${quantity} ${symbol} @ ${price} (${reason}, PnL: ${pnl.toFixed(2)})`)
}

async function generateSignals(): Promise<TradingSignal[]> {
  const signals: TradingSignal[] = []

  for (const symbol of CONFIG.SYMBOLS) {
    try {
      // Fetch klines from Binance
      const klines = await fetchKlines(symbol, '1h', 250)
      if (klines.length < 200) continue

      // Calculate indicators
      const closes = klines.map(k => k.close)
      const rsi = calculateRSI(closes, 14)
      const { macd, signal: macdSignal, histogram } = calculateMACD(closes)
      const { ema20, ema50, ema200 } = calculateEMAs(closes)
      const { upper, lower } = calculateBollingerBands(closes, 20, 2)

      const currentPrice = closes[closes.length - 1]
      const currentRSI = rsi[rsi.length - 1]
      const currentMACD = macd[macd.length - 1]
      const currentMACDSignal = macdSignal[macdSignal.length - 1]
      const currentHistogram = histogram[histogram.length - 1]
      const currentEMA20 = ema20[ema20.length - 1]
      const currentEMA50 = ema50[ema50.length - 1]
      const currentEMA200 = ema200[ema200.length - 1]
      const currentUpper = upper[upper.length - 1]
      const currentLower = lower[lower.length - 1]

      // Generate signal
      let score = 50 // Neutral start
      const reasons: string[] = []

      // RSI
      if (currentRSI < 30) { score += 15; reasons.push('RSI oversold') }
      else if (currentRSI < 40) { score += 8 }
      else if (currentRSI > 70) { score -= 15; reasons.push('RSI overbought') }
      else if (currentRSI > 60) { score -= 8 }

      // MACD
      if (currentHistogram > 0 && histogram[histogram.length - 2] < 0) {
        score += 15; reasons.push('MACD bullish crossover')
      } else if (currentHistogram < 0 && histogram[histogram.length - 2] > 0) {
        score -= 15; reasons.push('MACD bearish crossover')
      } else if (currentHistogram > 0) {
        score += 5
      } else {
        score -= 5
      }

      // EMA Trend
      if (currentPrice > currentEMA20 && currentEMA20 > currentEMA50 && currentEMA50 > currentEMA200) {
        score += 15; reasons.push('Strong uptrend (EMA alignment)')
      } else if (currentPrice < currentEMA20 && currentEMA20 < currentEMA50 && currentEMA50 < currentEMA200) {
        score -= 15; reasons.push('Strong downtrend (EMA alignment)')
      } else if (currentPrice > currentEMA50) {
        score += 5
      } else {
        score -= 5
      }

      // Bollinger Bands
      if (currentPrice < currentLower) {
        score += 10; reasons.push('Price below lower BB')
      } else if (currentPrice > currentUpper) {
        score -= 10; reasons.push('Price above upper BB')
      }

      // Determine signal type
      let signalType: TradingSignal['signal']
      if (score >= 75) signalType = 'STRONG_BUY'
      else if (score >= 60) signalType = 'BUY'
      else if (score <= 25) signalType = 'STRONG_SELL'
      else if (score <= 40) signalType = 'SELL'
      else signalType = 'HOLD'

      // Calculate stop-loss and take-profit
      const atr = calculateATR(klines, 14)
      const stopLoss = currentPrice - (atr * 2)
      const takeProfit = currentPrice + (atr * 3)

      signals.push({
        symbol,
        signal: signalType,
        score: Math.max(0, Math.min(100, score)),
        price: currentPrice,
        stopLoss,
        takeProfit,
      })

    } catch (error) {
      console.error(`Error generating signal for ${symbol}:`, error)
    }
  }

  return signals
}

async function fetchKlines(symbol: string, interval: string, limit: number): Promise<OHLCV[]> {
  const url = `https://api.binance.com/api/v3/klines?symbol=${symbol}&interval=${interval}&limit=${limit}`
  const response = await fetch(url)
  const data = await response.json()

  return data.map((k: any[]) => ({
    open: parseFloat(k[1]),
    high: parseFloat(k[2]),
    low: parseFloat(k[3]),
    close: parseFloat(k[4]),
    volume: parseFloat(k[5]),
  }))
}

// Technical Indicators
function calculateRSI(closes: number[], period: number): number[] {
  const rsi: number[] = []
  let gains = 0, losses = 0

  for (let i = 1; i <= period; i++) {
    const change = closes[i] - closes[i - 1]
    if (change > 0) gains += change
    else losses -= change
  }

  let avgGain = gains / period
  let avgLoss = losses / period
  rsi.push(100 - (100 / (1 + avgGain / avgLoss)))

  for (let i = period + 1; i < closes.length; i++) {
    const change = closes[i] - closes[i - 1]
    if (change > 0) {
      avgGain = (avgGain * (period - 1) + change) / period
      avgLoss = (avgLoss * (period - 1)) / period
    } else {
      avgGain = (avgGain * (period - 1)) / period
      avgLoss = (avgLoss * (period - 1) - change) / period
    }
    rsi.push(100 - (100 / (1 + avgGain / avgLoss)))
  }

  return rsi
}

function calculateEMA(data: number[], period: number): number[] {
  const ema: number[] = []
  const k = 2 / (period + 1)

  let sum = 0
  for (let i = 0; i < period; i++) sum += data[i]
  ema.push(sum / period)

  for (let i = period; i < data.length; i++) {
    ema.push(data[i] * k + ema[ema.length - 1] * (1 - k))
  }

  return ema
}

function calculateEMAs(closes: number[]) {
  return {
    ema20: calculateEMA(closes, 20),
    ema50: calculateEMA(closes, 50),
    ema200: calculateEMA(closes, 200),
  }
}

function calculateMACD(closes: number[]) {
  const ema12 = calculateEMA(closes, 12)
  const ema26 = calculateEMA(closes, 26)

  const macd: number[] = []
  const minLen = Math.min(ema12.length, ema26.length)
  const offset = ema12.length - minLen

  for (let i = 0; i < minLen; i++) {
    macd.push(ema12[i + offset] - ema26[i])
  }

  const signal = calculateEMA(macd, 9)
  const histogram: number[] = []
  const sigOffset = macd.length - signal.length

  for (let i = 0; i < signal.length; i++) {
    histogram.push(macd[i + sigOffset] - signal[i])
  }

  return { macd, signal, histogram }
}

function calculateBollingerBands(closes: number[], period: number, stdDev: number) {
  const upper: number[] = []
  const lower: number[] = []

  for (let i = period - 1; i < closes.length; i++) {
    const slice = closes.slice(i - period + 1, i + 1)
    const mean = slice.reduce((a, b) => a + b, 0) / period
    const variance = slice.reduce((sum, val) => sum + Math.pow(val - mean, 2), 0) / period
    const std = Math.sqrt(variance)

    upper.push(mean + stdDev * std)
    lower.push(mean - stdDev * std)
  }

  return { upper, lower }
}

function calculateATR(klines: OHLCV[], period: number): number {
  const trueRanges: number[] = []

  for (let i = 1; i < klines.length; i++) {
    const high = klines[i].high
    const low = klines[i].low
    const prevClose = klines[i - 1].close

    const tr = Math.max(
      high - low,
      Math.abs(high - prevClose),
      Math.abs(low - prevClose)
    )
    trueRanges.push(tr)
  }

  const recentTRs = trueRanges.slice(-period)
  return recentTRs.reduce((a, b) => a + b, 0) / period
}

function formatSymbol(binanceSymbol: string): string {
  // BTCUSDT -> BTC/USDT
  if (binanceSymbol.endsWith('USDT')) {
    return binanceSymbol.replace('USDT', '/USDT')
  }
  return binanceSymbol
}
