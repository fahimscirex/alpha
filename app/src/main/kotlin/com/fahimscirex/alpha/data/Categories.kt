package com.fahimscirex.alpha.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Starter categories and the merchant words that pick them. Matching is a case-insensitive
 * "merchant contains pattern", longest pattern first, so "amazon prime" beats "amazon"; a
 * pattern starting with "=" must equal the whole merchant ("=bkash" is not "bKash fee").
 * Everything here is editable in the app; these only seed a new database.
 */
val DEFAULT_CATEGORIES: List<Triple<String, String, List<String>>> = listOf(
    Triple("Food & Dining", "🍔", listOf("foodpanda", "pathao food", "kfc", "pizza", "restaurant", "cafe", "brew", "confection", "bfc")),
    Triple("Groceries", "🛒", listOf("shwapno", "meena bazar", "agora", "unimart", "chaldal", "gemcon", "mb ecb")),
    Triple("Transport", "🚌", listOf("uber", "pathao", "rapidpass", "railway", "shohoz", "2 go")),
    Triple("Bills & Utilities", "💡", listOf("desco", "dpdc", "wasa", "titas", "utility", "internet", "bas network", "basnet", "btcl", "u need host", "ek pay")),
    Triple("Mobile & Recharge", "📱", listOf("recharge", "grameenphone", "robi", "banglalink", "teletalk", "airtel", "skitto")),
    Triple("Shopping", "🛍️", listOf("daraz", "aarong", "star tech", "ryans", "aliexpress", "amazon", "bimboos", "lestylista", "style mart", "leather", "batighar", "portonics", "best buy", "anzaar", "stylevana", "aci logistics")),
    Triple("Health", "💊", listOf("pharma", "doctime", "hospital", "clinic", "skin cafe")),
    Triple("Education", "🎓", listOf("university", "college", "fees payment", "bup", "nstu", "school", "cimea", "admission", "software shop", "bdjobs", "consorzio")),
    Triple("Subscriptions", "📺", listOf("netflix", "spotify", "google one", "youtube", "chorki", "binge", "amazon prime", "microsoft", "canva", "cloudflare", "windscribe", "proton", "deepseek", "paddle", "decodo", "facebk", "google *", "porkbun", "sendinblue", "commandcode", "paleblue", "capcut", "oracle")),
    Triple("Fees & Charges", "🏦", listOf(" fee", "vat on", "charges", "tax on", "excise", "cheque issuance", "loan repayment")),
    Triple("Cash", "💵", listOf("cash withdrawal", "cash out", "atm")),
    Triple("Travel", "✈️", listOf("airbnb", "embassy", "spotahome", "hotel", "welcome associatio")),
    Triple("Entertainment", "🎬", listOf("cineplex", "blockbuster")),
    // Transfers between own accounts are linked and excluded from spending, so what is left
    // under these labels went to other people.
    Triple("Sent to others", "👥", listOf("npsb", "account transfer", "card transfer", "bank transfer", "citytouch transfer",
        "=bkash", "=nagad", "=tap", "=rocket")),
    Triple("Income", "💰", listOf("interest", "cashback", "remittance", "payoneer", "salary", "disbursement")),
    Triple("Other", "📦", emptyList()),
)

fun seedCategories(db: SupportSQLiteDatabase) {
    for ((name, emoji, patterns) in DEFAULT_CATEGORIES) {
        val id = db.insert("Category", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
            put("name", name); put("emoji", emoji)
        })
        for (p in patterns) db.insert("CategoryRule", SQLiteDatabase.CONFLICT_IGNORE, ContentValues().apply {
            put("pattern", p); put("categoryId", id); put("user", 0)
        })
    }
}

/** First rule whose pattern the merchant contains; [rules] must come ordered from [MoneyDao.rules]. */
fun categoryFor(merchant: String?, rules: List<CategoryRule>): Long? {
    val m = merchant?.lowercase() ?: return null
    return rules.firstOrNull { r -> if (r.pattern.startsWith("=")) m == r.pattern.substring(1) else m.contains(r.pattern) }?.categoryId
}
