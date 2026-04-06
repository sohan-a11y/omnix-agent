package com.omnix.agent.skills

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightweight NSE/BSE stock quote client.
 * Uses Yahoo Finance v8 API (free, no key required).
 * Results cached in memory with 60-second TTL.
 */
object StockClient {

    @Serializable
    data class StockQuote(
        val symbol: String,
        val name: String,
        val price: Double,
        val change: Double,
        val changePct: Double,
        val currency: String = "INR",
        val exchange: String = "NSE",
        val fetchedAt: Long = System.currentTimeMillis()
    )

    private val cache = mutableMapOf<String, StockQuote>()
    private const val CACHE_TTL_MS = 60_000L
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetch quote for a symbol like "RELIANCE", "TCS", "INFY".
     * Appends ".NS" for NSE or ".BO" for BSE automatically.
     */
    suspend fun getQuote(symbol: String): Result<StockQuote> = withContext(Dispatchers.IO) {
        val key = symbol.uppercase().trim()

        // Return from cache if fresh
        cache[key]?.let { cached ->
            if (System.currentTimeMillis() - cached.fetchedAt < CACHE_TTL_MS) {
                return@withContext Result.success(cached)
            }
        }

        val nseSymbol = if (key.contains(".")) key else "$key.NS"
        return@withContext try {
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/$nseSymbol?interval=1d&range=1d"
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            if (connection.responseCode != 200) {
                return@withContext Result.failure(Exception("HTTP ${connection.responseCode}"))
            }

            val raw = connection.inputStream.bufferedReader().readText()
            val quote = parseYahooResponse(key, raw)
            cache[key] = quote
            Result.success(quote)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseYahooResponse(symbol: String, raw: String): StockQuote {
        // Extract price and change from Yahoo Finance v8 JSON
        val priceRegex = """"regularMarketPrice":\{"raw":([\d.]+)""".toRegex()
        val changeRegex = """"regularMarketChange":\{"raw":(-?[\d.]+)""".toRegex()
        val changePctRegex = """"regularMarketChangePercent":\{"raw":(-?[\d.]+)""".toRegex()
        val nameRegex = """"longName":"([^"]+)"""".toRegex()
        val currencyRegex = """"currency":"([^"]+)"""".toRegex()

        val price = priceRegex.find(raw)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val change = changeRegex.find(raw)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val changePct = changePctRegex.find(raw)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val name = nameRegex.find(raw)?.groupValues?.get(1) ?: symbol
        val currency = currencyRegex.find(raw)?.groupValues?.get(1) ?: "INR"

        return StockQuote(
            symbol = symbol,
            name = name,
            price = price,
            change = change,
            changePct = changePct,
            currency = currency
        )
    }

    /** Format a quote for TTS output: "Reliance is at 2,845 rupees, up 1.2 percent" */
    fun formatForSpeech(quote: StockQuote): String {
        val direction = if (quote.change >= 0) "up" else "down"
        val absPct = String.format("%.1f", Math.abs(quote.changePct))
        val price = String.format("%.0f", quote.price)
        return "${quote.name} is at $price rupees, $direction $absPct percent"
    }
}
