package com.example.veromuistio.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val date: String,
    val description: String,
    val amount: Double,
    val type: TransactionType,
    val category: String,
    val receiptPaths: List<String> = emptyList(),
    val vatRate: Double = 0.0,
    // Mileage specific fields
    val mileage: Double? = null,
    val ratePerKm: Double? = null,
    val purpose: String? = null,
    val destination: String? = null,
    val withholdingTax: Double? = null,
    val workHours: Double? = null,
    // Delivery sale (Hankintakauppa) fields
    val isDeliverySale: Boolean = false,
    val cubicMeters: Double? = null,
    val workValuePerUnit: Double? = null
) {
    val workValue: Double
        get() = if (isDeliverySale) (cubicMeters ?: 0.0) * (workValuePerUnit ?: 0.0) else 0.0

    val timberValue: Double
        get() = amount - workValue

    val vatAmount: Double
        get() = if (type == TransactionType.MILEAGE) 0.0 else {
            // Hankintatyö on ALV 0%, joten lasketaan ALV vain puun osuudesta (amount - workValue)
            val taxableAmount = if (isDeliverySale) amount - workValue else amount
            taxableAmount * (vatRate / (100 + vatRate))
        }
    
    val totalAmount: Double
        get() = if (type == TransactionType.MILEAGE) (mileage ?: 0.0) * (ratePerKm ?: 0.0) else amount
}

enum class TransactionType {
    INCOME, EXPENSE, MILEAGE
}

class Converters {
    @TypeConverter
    fun fromString(value: String?): List<String> {
        return value?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
    }

    @TypeConverter
    fun fromList(list: List<String>?): String {
        return list?.joinToString("|") ?: ""
    }
}
