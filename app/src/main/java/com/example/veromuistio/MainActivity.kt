package com.example.veromuistio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.core.content.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.veromuistio.data.*
import com.example.veromuistio.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.*

class VeromuistioViewModel(private val dao: TransactionDao, context: Context) : ViewModel() {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val txs: StateFlow<List<Transaction>> = dao.getAllTransactions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    var isVatRegistered by mutableStateOf(prefs.getBoolean("is_vat_registered", true))
        private set

    var isDarkTheme by mutableStateOf(prefs.getBoolean("is_dark_theme", false))
        private set

    var isFirstRun by mutableStateOf(prefs.getBoolean("is_first_run", true))
        private set

    fun toggleVat(value: Boolean) {
        isVatRegistered = value
        prefs.edit().putBoolean("is_vat_registered", value).apply()
    }

    fun toggleTheme(value: Boolean) {
        isDarkTheme = value
        prefs.edit().putBoolean("is_dark_theme", value).apply()
    }

    fun completeOnboarding() {
        isFirstRun = false
        prefs.edit().putBoolean("is_first_run", false).apply()
    }

    fun save(t: Transaction) = viewModelScope.launch { if (t.id == 0) dao.insertTransaction(t) else dao.updateTransaction(t) }
    fun saveAll(list: List<Transaction>) = viewModelScope.launch { dao.insertTransactions(list) }
    fun delete(t: Transaction) = viewModelScope.launch { dao.deleteTransaction(t) }

    fun getYearlyCubicMeters(year: String, list: List<Transaction>): Double {
        return list.filter { it.isDeliverySale && it.date.endsWith(".$year") }.sumOf { it.cubicMeters ?: 0.0 }
    }
}

class MainActivity : ComponentActivity() {
    private var tempUri: Uri? = null
    private var onImg: ((Uri) -> Unit)? = null
    private lateinit var vm: VeromuistioViewModel
    private var txsToPdf: List<Transaction> = emptyList()
    private var pdfYear: String = ""

    private val takePic = registerForActivityResult(ActivityResultContracts.TakePicture()) { if (it) tempUri?.let { u -> onImg?.invoke(u) } }
    private val pickMed = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> onImg?.invoke(u) } }
    private val reqPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) startCam() else Toast.makeText(this, "Lupa tarvitaan", Toast.LENGTH_SHORT).show() }
    
    private val createZip = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { u -> lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(u)?.use { os ->
                    ZipOutputStream(os).use { zos ->
                        val csv = vm.txs.value.joinToString("\n") { 
                            "${it.id};${it.date};${it.description.replace(";"," ")};${it.amount};${it.type};${it.category};${it.vatRate};${it.mileage ?: ""};${it.ratePerKm ?: ""};${it.purpose?.replace(";"," ") ?: ""};${it.receiptPaths.joinToString("|")};${it.destination?.replace(";"," ") ?: ""};${it.withholdingTax ?: ""};${it.isDeliverySale};${it.cubicMeters ?: ""};${it.workValuePerUnit ?: ""}"
                        }
                        zos.putNextEntry(ZipEntry("veromuistio_data.csv"))
                        zos.write(csv.toByteArray())
                        zos.closeEntry()

                        vm.txs.value.forEach { t ->
                            t.receiptPaths.forEach { path ->
                                try {
                                    val imageUri = Uri.parse(path)
                                    contentResolver.openInputStream(imageUri)?.use { ins ->
                                        val fileName = path.split("/").last()
                                        zos.putNextEntry(ZipEntry("kuitit/$fileName"))
                                        ins.copyTo(zos)
                                        zos.closeEntry()
                                    }
                                } catch (e: Exception) {}
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Varmuuskopio tallennettu (ZIP)", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Tallennusvirhe", Toast.LENGTH_SHORT).show() }
            }
        } }
    }

    private val openZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u -> lifecycleScope.launch(Dispatchers.IO) {
            try {
                val existingTxs = vm.txs.value
                val importedTxs = mutableListOf<Transaction>()
                contentResolver.openInputStream(u)?.use { ins ->
                    ZipInputStream(ins).use { zis ->
                        var entry = zis.getNextEntry()
                        while (entry != null) {
                            if (entry.name == "veromuistio_data.csv") {
                                val content = zis.bufferedReader().readText()
                                content.split("\n").filter { it.isNotBlank() }.forEach { line ->
                                    val p = line.split(";")
                                    if (p.size >= 11) {
                                        val pathsStr = p[10].takeIf { it != "null" && it.isNotEmpty() }
                                        val newPaths = mutableListOf<String>()
                                        pathsStr?.split("|")?.forEach { path ->
                                            if (path.isNotEmpty()) {
                                                val fileName = path.split("/").last()
                                                val destFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), fileName)
                                                newPaths.add(Uri.fromFile(destFile).toString())
                                            }
                                        }

                                        val dateVal = p[1]
                                        val descVal = p[2]
                                        val amountVal = p[3].toDoubleOrNull() ?: 0.0
                                        val typeVal = try { TransactionType.valueOf(p[4]) } catch(e: Exception) { TransactionType.EXPENSE }
                                        val catVal = p[5]
                                        val vatVal = p[6].toDoubleOrNull() ?: 0.0
                                        val milVal = p[7].toDoubleOrNull()
                                        val rateVal = p[8].toDoubleOrNull()
                                        val purpVal = p[9].takeIf { it != "null" && it.isNotEmpty() }
                                        val destVal = if (p.size > 11) p[11].takeIf { it != "null" && it.isNotEmpty() } else null
                                        val withVal = if (p.size > 12) p[12].toDoubleOrNull() else null
                                        val isDelVal = if (p.size > 13) p[13].toBoolean() else false
                                        val m3Val = if (p.size > 14) p[14].toDoubleOrNull() else null
                                        val unitVal = if (p.size > 15) p[15].toDoubleOrNull() else null

                                        val isDuplicate = existingTxs.any { 
                                            it.date == dateVal && it.description == descVal && it.amount == amountVal &&
                                            it.type == typeVal && it.category == catVal && it.isDeliverySale == isDelVal
                                        }

                                        if (!isDuplicate) {
                                            importedTxs.add(Transaction(
                                                id = 0, date = dateVal, description = descVal, amount = amountVal,
                                                type = typeVal, category = catVal, vatRate = vatVal,
                                                mileage = milVal, ratePerKm = rateVal, purpose = purpVal,
                                                receiptPaths = newPaths, destination = destVal, withholdingTax = withVal,
                                                isDeliverySale = isDelVal, cubicMeters = m3Val, workValuePerUnit = unitVal
                                            ))
                                        }
                                    }
                                }
                            } else if (entry.name.startsWith("kuitit/")) {
                                val fileName = entry.name.split("/").last()
                                val destFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), fileName)
                                destFile.outputStream().use { zis.copyTo(it) }
                            }
                            zis.closeEntry()
                            entry = zis.getNextEntry()
                        }
                    }
                }
                if (importedTxs.isNotEmpty()) {
                    vm.saveAll(importedTxs)
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Tuotu ${importedTxs.size} uutta tapahtumaa", Toast.LENGTH_SHORT).show() }
                } else {
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Ei uusia tuotavia tapahtumia", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Tuontivirhe", Toast.LENGTH_SHORT).show() }
            }
        } }
    }

    private val createPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { u -> lifecycleScope.launch(Dispatchers.IO) {
            try {
                val vatVal = vm.isVatRegistered
                contentResolver.openOutputStream(u)?.use { generatePdf(txsToPdf, pdfYear, it, vatVal) }
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "PDF tallennettu", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "PDF-virhe", Toast.LENGTH_SHORT).show() }
            }
        } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.getDatabase(this).transactionDao()
        setContent { 
            vm = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory { override fun <T : ViewModel> create(modelClass: Class<T>): T = VeromuistioViewModel(dao, applicationContext) as T })
            VeromuistioTheme(darkTheme = vm.isDarkTheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { 
                    if (vm.isFirstRun) {
                        OnboardingScreen(vm)
                    } else {
                        VeromuistioApp(
                            vm = vm, 
                            onCam = { onImg = it; checkPerm() }, 
                            onGal = { onImg = it; pickMed.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, 
                            onExp = { createZip.launch("veromuistio_varmuuskopio.zip") }, 
                            onImp = { openZip.launch(arrayOf("application/zip")) },
                            onPdf = { list, year -> 
                                txsToPdf = list
                                pdfYear = year
                                createPdf.launch("veromuistio_yhteenveto_$year.pdf")
                            }
                        ) 
                    }
                }
            }
        }
    }

    private fun checkPerm() { if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCam() else reqPerm.launch(Manifest.permission.CAMERA) }
    private fun startCam() { val f = File.createTempFile("IMG_${System.currentTimeMillis()}", ".jpg", getExternalFilesDir(Environment.DIRECTORY_PICTURES)); tempUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", f); tempUri?.let { takePic.launch(it) } }

    private fun generatePdf(txs: List<Transaction>, year: String, out: OutputStream, isVatRegistered: Boolean) {
        val doc = PdfDocument()
        val paint = Paint()
        val titlePaint = Paint().apply { isFakeBoldText = true; textSize = 20f }
        val headerPaint = Paint().apply { isFakeBoldText = true; textSize = 14f }
        val textPaint = Paint().apply { textSize = 12f }
        val boldPaint = Paint().apply { isFakeBoldText = true; textSize = 12f }
        val grayPaint = Paint().apply { textSize = 10f; color = android.graphics.Color.GRAY }
        val linePaint = Paint().apply { strokeWidth = 1f; color = android.graphics.Color.LTGRAY }
        
        val df = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        
        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNum).create()
        var page = doc.startPage(pageInfo)
        var canvas = page.canvas
        var y = 60f

        canvas.drawText("Metsätalouden veromuistio", 50f, y, titlePaint)
        y += 25f
        canvas.drawText("Yhteenveto tilikaudelta: $year", 50f, y, headerPaint)
        y += 20f
        canvas.drawText("Luotu: ${df.format(Date())} | Profiili: ${if (isVatRegistered) "ALV-velvollinen" else "Ei ALV-velvollinen"}", 50f, y, grayPaint)
        y += 35f

        val incTxs = txs.filter { it.type == TransactionType.INCOME }
        val exTxs = txs.filter { it.type == TransactionType.EXPENSE }
        val miTxs = txs.filter { it.type == TransactionType.MILEAGE }
        val deprCats = listOf("Koneet ja kalusto", "Rakennukset", "Ojat ja tiet")
        val deprTxs = txs.filter { it.category in deprCats }

        val totalIncBrutto = incTxs.sumOf { it.totalAmount }
        val totalIncVat = incTxs.sumOf { it.vatAmount }
        val totalWorkValue = incTxs.filter { it.isDeliverySale }.sumOf { it.workValue }
        val totalExBrutto = exTxs.filter { it.category !in deprCats }.sumOf { it.totalAmount }
        val totalExVat = exTxs.filter { it.category !in deprCats }.sumOf { it.vatAmount }
        val deprBrutto = deprTxs.sumOf { it.totalAmount }
        val deprVat = deprTxs.sumOf { it.vatAmount }
        val mileageSum = miTxs.sumOf { it.totalAmount }
        val withholdingSum = incTxs.sumOf { it.withholdingTax ?: 0.0 }

        canvas.drawText("TALOUSYHTEENVETO", 50f, y, headerPaint); y += 5f
        canvas.drawLine(50f, y, 545f, y, linePaint); y += 25f

        fun drawRow(label: String, amount: Double, isBold: Boolean = false) {
            val p = if (isBold) boldPaint else textPaint
            canvas.drawText(label, 70f, y, p)
            val amtStr = String.format("%.2f €", amount)
            canvas.drawText(amtStr, 520f - p.measureText(amtStr), y, p)
            y += 20f
        }

        if (isVatRegistered) {
            drawRow("Tulot yhteensä (alv 0%)", totalIncBrutto - totalIncVat)
            if (totalWorkValue > 0) drawRow("Hankintatyön arvo (alv 0%)", totalWorkValue)
            drawRow("Menot yhteensä (alv 0%)", totalExBrutto - totalExVat)
            drawRow("Matkakulut (verovapaat)", mileageSum)
            drawRow("Poistot (alv 0%)", deprBrutto - deprVat)
            y += 10f
            // NETTOTULOS: Vähennetään myös hankintatyön arvo, koska se on ansiotuloa/verovapaata eikä osa metsätalouden puhdasta pääomatuloa.
            drawRow("NETTOTULOS", (totalIncBrutto - totalIncVat) - totalWorkValue - (totalExBrutto - totalExVat) - mileageSum - (deprBrutto - deprVat), true)
            if (withholdingSum > 0) { y += 10f; drawRow("Maksetut ennakonpidätykset", withholdingSum) }
        } else {
            drawRow("Tulot yhteensä (brutto)", totalIncBrutto)
            if (totalWorkValue > 0) drawRow("Hankintatyön arvo", totalWorkValue)
            drawRow("Menot yhteensä (brutto)", totalExBrutto)
            drawRow("Matkakulut", mileageSum)
            drawRow("Poistot (brutto)", deprBrutto)
            y += 10f
            // NETTOTULOS: Vähennetään hankintatyön arvo.
            drawRow("NETTOTULOS", totalIncBrutto - totalWorkValue - totalExBrutto - mileageSum - deprBrutto, true)
            if (withholdingSum > 0) { y += 10f; drawRow("Maksetut ennakonpidätykset", withholdingSum) }
        }
        y += 30f

        if (isVatRegistered || txs.any { it.vatRate > 0 }) {
            canvas.drawText("ALV-LASKELMA", 50f, y, headerPaint); y += 5f
            canvas.drawLine(50f, y, 545f, y, linePaint); y += 25f
            val tInV = incTxs.sumOf { it.vatAmount }; val tExV = exTxs.sumOf { it.vatAmount }; val diff = tInV - tExV
            drawRow("Myyntien ALV (tilitettävä)", tInV)
            drawRow("Ostojen ALV (vähennettävä)", tExV)
            y += 10f
            drawRow(if (diff >= 0) "MAKSETTAVA ALV" else "PALAUTETTAVA ALV", Math.abs(diff), true)
            y += 30f
        }

        if (deprTxs.isNotEmpty()) {
            if (y > 700) { doc.finishPage(page); pageNum++; pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNum).create(); page = doc.startPage(pageInfo); canvas = page.canvas; y = 50f }
            canvas.drawText("POISTOT (ERITTELY)", 50f, y, headerPaint); y += 5f
            canvas.drawLine(50f, y, 545f, y, linePaint); y += 25f
            deprTxs.sortedBy { 
                val p = it.date.split(".")
                if (p.size == 3) (p[2].toIntOrNull() ?: 0) * 10000 + (p[1].toIntOrNull() ?: 0) * 100 + (p[0].toIntOrNull() ?: 0) else 0
            }.forEach { t ->
                if (y > 780) { doc.finishPage(page); pageNum++; pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNum).create(); page = doc.startPage(pageInfo); canvas = page.canvas; y = 50f }
                canvas.drawText("${t.date}  ${t.description} (${t.category})", 70f, y, textPaint)
                val amtStr = String.format("%.2f €", if (isVatRegistered) t.totalAmount - t.vatAmount else t.totalAmount)
                canvas.drawText(amtStr, 520f - textPaint.measureText(amtStr), y, textPaint)
                y += 18f
            }
            y += 25f
        }

        if (miTxs.isNotEmpty()) {
            if (y > 700) { doc.finishPage(page); pageNum++; pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNum).create(); page = doc.startPage(pageInfo); canvas = page.canvas; y = 50f }
            canvas.drawText("AJOPÄIVÄKIRJA", 50f, y, headerPaint); y += 5f
            canvas.drawLine(50f, y, 545f, y, linePaint); y += 25f
            miTxs.sortedBy { 
                val p = it.date.split(".")
                if (p.size == 3) (p[2].toIntOrNull() ?: 0) * 10000 + (p[1].toIntOrNull() ?: 0) * 100 + (p[0].toIntOrNull() ?: 0) else 0
            }.forEach { t ->
                if (y > 780) { doc.finishPage(page); pageNum++; pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNum).create(); page = doc.startPage(pageInfo); canvas = page.canvas; y = 50f }
                val hoursStr = if (t.workHours != null) " | ${t.workHours} h" else ""
                canvas.drawText("${t.date}  ${t.purpose}${if (t.destination != null) " (${t.destination})" else ""} (${t.mileage} km$hoursStr)", 70f, y, textPaint)
                val amtStr = String.format("%.2f €", t.totalAmount)
                canvas.drawText(amtStr, 520f - textPaint.measureText(amtStr), y, textPaint)
                y += 18f
            }
            y += 30f
        }

        val detailTxs = txs.filter { it.type == TransactionType.INCOME || it.type == TransactionType.EXPENSE }.sortedBy { 
            val p = it.date.split(".")
            if (p.size == 3) (p[2].toIntOrNull() ?: 0) * 10000 + (p[1].toIntOrNull() ?: 0) * 100 + (p[0].toIntOrNull() ?: 0) else 0
        }
        if (detailTxs.isNotEmpty()) {
            doc.finishPage(page)
            detailTxs.forEach { t ->
                if (t.receiptPaths.isEmpty()) {
                    pageNum++
                    pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNum).create(); page = doc.startPage(pageInfo); canvas = page.canvas; y = 50f
                    canvas.drawText("${if (t.type == TransactionType.INCOME) "Tulotosite" else "Menotosite"}: ${t.description}", 50f, y, headerPaint); y += 20f
                    canvas.drawText("Päivämäärä: ${t.date} | Luokka: ${t.category}", 50f, y, grayPaint); y += 25f
                    canvas.drawLine(50f, y, 545f, y, linePaint); y += 20f
                    canvas.drawText("Summa (sis. alv):", 70f, y, boldPaint); val tStr = String.format("%.2f €", t.totalAmount); canvas.drawText(tStr, 520f - boldPaint.measureText(tStr), y, boldPaint); y += 20f
                    if (t.isDeliverySale) {
                        canvas.drawText("- josta puun tulo:", 70f, y, textPaint); val timStr = String.format("%.2f €", t.timberValue); canvas.drawText(timStr, 520f - textPaint.measureText(timStr), y, textPaint); y += 18f
                        canvas.drawText("- josta hankintatyö (${t.cubicMeters} m3):", 70f, y, textPaint); val worStr = String.format("%.2f €", t.workValue); canvas.drawText(worStr, 520f - textPaint.measureText(worStr), y, textPaint); y += 20f
                    }
                    if (isVatRegistered || t.vatRate > 0) {
                        canvas.drawText("ALV osuus (${t.vatRate}%):", 70f, y, textPaint); val vStr = String.format("%.2f €", t.vatAmount); canvas.drawText(vStr, 520f - textPaint.measureText(vStr), y, textPaint); y += 18f
                    }
                    if ((t.withholdingTax ?: 0.0) > 0) {
                        canvas.drawText("Ennakonpidätys:", 70f, y, textPaint); val wStr = String.format("%.2f €", t.withholdingTax); canvas.drawText(wStr, 520f - textPaint.measureText(wStr), y, textPaint); y += 20f
                    }
                    y += 30f; canvas.drawText("Ei liitettyä kuvaa.", 50f, y, grayPaint)
                    doc.finishPage(page)
                } else {
                    t.receiptPaths.forEachIndexed { index, path ->
                        pageNum++; pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNum).create(); page = doc.startPage(pageInfo); canvas = page.canvas; y = 50f
                        canvas.drawText("${if (t.type == TransactionType.INCOME) "Tulotosite" else "Menotosite"}: ${t.description} (${index + 1}/${t.receiptPaths.size})", 50f, y, headerPaint); y += 20f
                        canvas.drawText("Päivämäärä: ${t.date} | Luokka: ${t.category}", 50f, y, grayPaint); y += 25f
                        canvas.drawLine(50f, y, 545f, y, linePaint); y += 20f
                        canvas.drawText("Summa (sis. alv):", 70f, y, boldPaint); val tStr = String.format("%.2f €", t.totalAmount); canvas.drawText(tStr, 520f - boldPaint.measureText(tStr), y, boldPaint); y += 20f
                        if (t.isDeliverySale) {
                            canvas.drawText("- josta puun tulo:", 70f, y, textPaint); val timStr = String.format("%.2f €", t.timberValue); canvas.drawText(timStr, 520f - textPaint.measureText(timStr), y, textPaint); y += 18f
                            canvas.drawText("- josta hankintatyö (${t.cubicMeters} m3):", 70f, y, textPaint); val worStr = String.format("%.2f €", t.workValue); canvas.drawText(worStr, 520f - textPaint.measureText(worStr), y, textPaint); y += 20f
                        }
                        if (isVatRegistered || t.vatRate > 0) {
                            canvas.drawText("ALV osuus (${t.vatRate}%):", 70f, y, textPaint); val vStr = String.format("%.2f €", t.vatAmount); canvas.drawText(vStr, 520f - textPaint.measureText(vStr), y, textPaint); y += 18f
                        }
                        if ((t.withholdingTax ?: 0.0) > 0) {
                            canvas.drawText("Ennakonpidätys:", 70f, y, textPaint); val wStr = String.format("%.2f €", t.withholdingTax); canvas.drawText(wStr, 520f - textPaint.measureText(wStr), y, textPaint); y += 20f
                        }
                        y += 10f
                        try {
                            val uri = Uri.parse(path)
                            contentResolver.openInputStream(uri)?.use { ins ->
                                val original = BitmapFactory.decodeStream(ins)
                                if (original != null) {
                                    val scale = Math.min(500f / original.width, 500f / original.height)
                                    val scaled = Bitmap.createScaledBitmap(original, (original.width * scale).toInt(), (original.height * scale).toInt(), true)
                                    canvas.drawBitmap(scaled, 50f, y, paint)
                                }
                            }
                        } catch (e: Exception) {}
                        doc.finishPage(page)
                    }
                }
            }
            page = null
        }

        if (page != null) doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(vm: VeromuistioViewModel) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(SoftWood)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> OnboardingPage(
                    title = "Tervetuloa Veromuistioon",
                    description = "Helppo tapa hallita metsätalouden tuloja, menoja ja muistiinpanoja suoraan taskussasi.",
                    icon = Icons.Default.Forest,
                    color = ForestGreen
                )
                1 -> OnboardingSelectionPage(vm)
                2 -> OnboardingPage(
                    title = "Kaikki valmista!",
                    description = "Voit nyt aloittaa sovelluksen käytön. Lisää ensimmäinen tapahtumasi plus-painikkeesta.",
                    icon = Icons.Default.Celebration,
                    color = ForestGreen,
                    showButton = true,
                    onFinish = { vm.completeOnboarding() }
                )
            }
        }

        Row(
            Modifier.height(50.dp).fillMaxWidth().align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(3) { iteration ->
                val color = if (pagerState.currentPage == iteration) ForestGreen else Color.LightGray
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(color)
                        .size(10.dp)
                )
            }
        }
    }
}

@Composable
fun OnboardingPage(title: String, description: String, icon: ImageVector, color: Color, showButton: Boolean = false, onFinish: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, modifier = Modifier.size(120.dp), tint = color)
        Spacer(Modifier.height(32.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(description, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = DeepBark)
        
        if (showButton) {
            Spacer(Modifier.height(48.dp))
            Button(
                onClick = onFinish,
                colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Aloita käyttö", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun OnboardingSelectionPage(vm: VeromuistioViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.AccountBalance, null, modifier = Modifier.size(80.dp), tint = ForestGreen)
        Spacer(Modifier.height(32.dp))
        Text("Arvonlisäverotus", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = ForestGreen)
        Spacer(Modifier.height(16.dp))
        Text("Valitse profiilisi. Voit muuttaa tätä myöhemmin asetuksista.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        
        OutlinedButton(
            onClick = { vm.toggleVat(true) },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(2.dp, if (vm.isVatRegistered) ForestGreen else Color.LightGray),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (vm.isVatRegistered) ForestGreen.copy(alpha = 0.1f) else Color.Transparent)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Business, null, tint = ForestGreen)
                Spacer(Modifier.width(12.dp))
                Text("ALV-velvollinen", color = DeepBark, fontWeight = FontWeight.Bold)
            }
        }
        
        Spacer(Modifier.height(16.dp))
        
        OutlinedButton(
            onClick = { vm.toggleVat(false) },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(2.dp, if (!vm.isVatRegistered) ForestGreen else Color.LightGray),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (!vm.isVatRegistered) ForestGreen.copy(alpha = 0.1f) else Color.Transparent)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, null, tint = ForestGreen)
                Spacer(Modifier.width(12.dp))
                Text("Ei ALV-velvollinen", color = DeepBark, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VeromuistioApp(vm: VeromuistioViewModel, onCam: ((Uri) -> Unit) -> Unit, onGal: ((Uri) -> Unit) -> Unit, onExp: () -> Unit, onImp: () -> Unit, onPdf: (List<Transaction>, String) -> Unit) {
    val allTxs by vm.txs.collectAsState(); var menuExp by remember { mutableStateOf(false) }; var moreExp by remember { mutableStateOf(false) }
    
    val currentYear = Calendar.getInstance().get(Calendar.YEAR).toString()
    var selYear by remember { mutableStateOf(currentYear) }
    
    val years = remember(allTxs) { 
        val yearsFromData = allTxs.mapNotNull { try { it.date.split(".").last() } catch (e: Exception) { null } }.distinct()
        (listOf("Kaikki", currentYear) + yearsFromData).distinct().sortedDescending()
    }

    val txs = remember(allTxs, selYear) {
        val filtered = if (selYear == "Kaikki") allTxs else allTxs.filter { it.date.endsWith(".$selYear") }
        filtered.sortedByDescending {
            val p = it.date.split(".")
            if (p.size == 3) (p[2].toIntOrNull() ?: 0) * 10000 + (p[1].toIntOrNull() ?: 0) * 100 + (p[0].toIntOrNull() ?: 0) else 0
        }
    }
    var showEdit by remember { mutableStateOf(false) }; var editingTransaction by remember { mutableStateOf<Transaction?>(null) }; var tab by remember { mutableIntStateOf(0) }
    
    Scaffold(topBar = { 
        Box(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(6.dp)
            ) {
                CenterAlignedTopAppBar(
                    title = { Text("Veromuistio ${if(selYear != "Kaikki") selYear else ""}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                    actions = {
                        IconButton({ menuExp = true }) { Icon(Icons.Default.CalendarToday, null, tint = MaterialTheme.colorScheme.onPrimary) }
                        DropdownMenu(menuExp, { menuExp = false }) { years.forEach { y -> DropdownMenuItem(text = { Text(y) }, onClick = { selYear = y; menuExp = false }, leadingIcon = { if(selYear == y) Icon(Icons.Default.Check, null) }) } }
                        IconButton({ moreExp = true }) { Icon(Icons.Default.MoreVert, null, tint = MaterialTheme.colorScheme.onPrimary) }
                        DropdownMenu(moreExp, { moreExp = false }) {
                            DropdownMenuItem(text = { Text(if (vm.isVatRegistered) "Profiili: ALV-velvollinen" else "Profiili: Ei ALV-velvollinen") }, onClick = { vm.toggleVat(!vm.isVatRegistered); moreExp = false }, leadingIcon = { Icon(if (vm.isVatRegistered) Icons.Default.Business else Icons.Default.Person, null) })
                            DropdownMenuItem(text = { Text(if (vm.isDarkTheme) "Teema: Tumma" else "Teema: Vaalea") }, onClick = { vm.toggleTheme(!vm.isDarkTheme); moreExp = false }, leadingIcon = { Icon(if (vm.isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode, null) })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Luo varmuuskopio") }, onClick = { onExp(); moreExp = false }, leadingIcon = { Icon(Icons.Default.Save, null) })
                            DropdownMenuItem(text = { Text("Tuo varmuuskopio") }, onClick = { onImp(); moreExp = false }, leadingIcon = { Icon(Icons.Default.UploadFile, null) })
                            DropdownMenuItem(text = { Text("Tallenna PDF") }, onClick = { onPdf(txs, selYear); moreExp = false }, leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) })
                        }
                    }
                )
                if (vm.isVatRegistered) {
                    TabRow(
                        tab,
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        indicator = { pos -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = AccentAmber) },
                        divider = {}
                    ) {
                        Tab(tab == 0, { tab = 0 }, text = { Text("Tapahtumat") }, icon = { Icon(Icons.AutoMirrored.Filled.List, null) })
                        Tab(tab == 1, { tab = 1 }, text = { Text("ALV") }, icon = { Icon(Icons.Default.Percent, null) })
                    }
                }
            }
        }
    }, floatingActionButton = { LargeFloatingActionButton({ editingTransaction = null; showEdit = true }, containerColor = AccentAmber, contentColor = DeepBark, shape = CircleShape) { Icon(Icons.Default.Add, null, Modifier.size(36.dp)) } }) { p ->
        Box(Modifier.padding(p).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (tab == 0 || !vm.isVatRegistered) Column { SummaryView(txs, vm.isVatRegistered, vm.isDarkTheme, vm); TransactionList(txs) { editingTransaction = it; showEdit = true } }
            else VatSummaryView(txs)
        }
        if (showEdit) TransactionDialog(editingTransaction, { showEdit = false }, { vm.save(it); showEdit = false }, { vm.delete(it); showEdit = false }, onCam, onGal, vm.isVatRegistered, vm)
    }
}

@Composable
fun SummaryView(txs: List<Transaction>, isVatRegistered: Boolean, isDarkTheme: Boolean, vm: VeromuistioViewModel) {
    val inc = txs.filter { it.type == TransactionType.INCOME }.sumOf { it.totalAmount }
    val workVal = txs.filter { it.isDeliverySale }.sumOf { it.workValue }
    val deprCats = listOf("Koneet ja kalusto", "Rakennukset", "Ojat ja tiet")
    val depr = if (isVatRegistered) txs.filter { it.category in deprCats }.sumOf { it.totalAmount - it.vatAmount } else txs.filter { it.category in deprCats }.sumOf { it.totalAmount }
    val exp = if (isVatRegistered) txs.filter { it.type == TransactionType.EXPENSE && it.category !in deprCats }.sumOf { it.totalAmount - it.vatAmount } else txs.filter { it.type == TransactionType.EXPENSE && it.category !in deprCats }.sumOf { it.totalAmount }
    val mil = txs.filter { it.type == TransactionType.MILEAGE }.sumOf { it.totalAmount }
    val withh = txs.filter { it.type == TransactionType.INCOME }.sumOf { it.withholdingTax ?: 0.0 }

    val inV = txs.filter { it.type == TransactionType.INCOME }.sumOf { it.vatAmount }
    val exV = txs.filter { it.type == TransactionType.EXPENSE }.sumOf { it.vatAmount }
    val vatDiff = inV - exV

    var showWorkInfo by remember { mutableStateOf(false) }

    if (showWorkInfo) {
        AlertDialog(
            onDismissRequest = { showWorkInfo = false },
            title = { Text("Hankintatyön arvo") },
            text = { Text("Hankintatyön arvo on osa kokonaistuloa, mutta se vähennetään metsätalouden puhtaasta pääomatulosta. Se ilmoitetaan verotuksessa ansiotulona (verovapaata 125 m³ asti).") },
            confirmButton = { TextButton({ showWorkInfo = false }) { Text("OK") } }
        )
    }

    Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(4.dp)) {
        Column(Modifier.padding(16.dp)) {
            SumRow("Tulot (sis. alv)", inc, IncomeGreen, Icons.AutoMirrored.Filled.TrendingUp)
            if (workVal > 0) SumRow("Hankintatyö", workVal, IncomeGreen.copy(alpha = 0.8f), Icons.Default.Handyman, onInfo = { showWorkInfo = true })
            SumRow(if (isVatRegistered) "Menot (alv 0%)" else "Menot (brutto)", exp, ExpenseRed, Icons.AutoMirrored.Filled.TrendingDown)
            SumRow("Matkat", mil, MileageBlue, Icons.Default.DirectionsCar)
            SumRow(if (isVatRegistered) "Poistot (alv 0%)" else "Poistot (brutto)", depr, Color(0xFF795548), Icons.Default.SettingsBackupRestore)
            
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(0.3f))
            
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Kassasaldo (sis. alv)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    val total = inc - workVal - exp - mil - depr
                    val amountColor = if (total >= 0) {
                        if (isDarkTheme) Color(0xFF81C784) else ForestGreen 
                    } else {
                        ExpenseRed
                    }
                    Text("${String.format("%.2f", total)} €", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = amountColor)
                }
                if (isVatRegistered && vatDiff > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(24.dp))
                            Text(
                                text = "Sisältää tilitettävää arvonlisäveroa",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Text(
                            text = "${String.format("%.2f", vatDiff)} €",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            if (withh > 0) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Maksetut ennakonpidätykset", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text("${String.format("%.2f", withh)} €", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
fun SumRow(l: String, a: Double, c: Color, i: ImageVector, onInfo: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) { 
            Icon(i, null, tint = c.copy(0.7f), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(l, color = MaterialTheme.colorScheme.onSurface.copy(0.6f))
            if (onInfo != null) {
                IconButton({ onInfo() }, Modifier.size(24.dp).padding(start = 4.dp)) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary.copy(0.5f), modifier = Modifier.size(14.dp))
                }
            }
        }
        Text("${String.format("%.2f", a)} €", color = c, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun VatSummaryView(txs: List<Transaction>) {
    val inV = txs.filter { it.type == TransactionType.INCOME }.sumOf { it.vatAmount }; val exV = txs.filter { it.type == TransactionType.EXPENSE }.sumOf { it.vatAmount }; val diff = inV - exV
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item { Text("ALV-yhteenveto", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Spacer(Modifier.height(16.dp)) }
        item { VatCard("Myyntien ALV", inV, IncomeGreen) }; item { VatCard("Ostojen ALV", exV, ExpenseRed) }
        item { Card(Modifier.fillMaxWidth().padding(vertical = 12.dp), colors = CardDefaults.cardColors(containerColor = if (diff < 0) Color(0xFFE3F2FD) else Color(0xFFFFF3E0)), elevation = CardDefaults.cardElevation(6.dp)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (diff < 0) "PALAUTETTAVA ALV" else "TILITETTÄVÄ ALV", style = MaterialTheme.typography.labelLarge, color = if (diff < 0) MileageBlue else Color(0xFFE65100))
                Text("${String.format("%.2f", Math.abs(diff))} €", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, color = if (diff < 0) MileageBlue else Color(0xFFE65100))
            }
        } }
    }
}

@Composable
fun VatCard(l: String, a: Double, c: Color) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp)) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(l, fontWeight = FontWeight.Medium); Text("${String.format("%.2f", a)} €", color = c, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
    }
}

@Composable
fun TransactionList(txs: List<Transaction>, onEdit: (Transaction) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(txs) { t -> Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onEdit(t) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            ListItem( headlineContent = { Text(if (t.type == TransactionType.MILEAGE) t.purpose ?: "Ajo" else t.description, fontWeight = FontWeight.SemiBold) }, supportingContent = { 
                val subText = if (t.type == TransactionType.MILEAGE) {
                    val hStr = if (t.workHours != null) " • ${t.workHours} h" else ""
                    "${t.mileage} km$hStr${if(t.destination != null) " -> ${t.destination}" else ""}"
                } else if(t.isDeliverySale) "${t.cubicMeters} m³" else t.category
                Text("${t.date} • $subText", style = MaterialTheme.typography.bodySmall) 
            }, leadingContent = {
                val icon = when(t.type) { TransactionType.INCOME -> Icons.Default.AddCircle; TransactionType.EXPENSE -> Icons.Default.RemoveCircle; else -> Icons.Default.DirectionsCar }
                val color = when(t.type) { TransactionType.INCOME -> IncomeGreen; TransactionType.EXPENSE -> ExpenseRed; else -> MileageBlue }
                Box(Modifier.size(40.dp).background(color.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = color) }
            }, trailingContent = { Column(horizontalAlignment = Alignment.End) {
                val c = when(t.type) { TransactionType.INCOME -> IncomeGreen; TransactionType.EXPENSE -> ExpenseRed; else -> MileageBlue }
                Text("${if (t.type == TransactionType.INCOME) "+" else "-"}${String.format("%.2f", t.totalAmount)} €", fontWeight = FontWeight.Bold, color = c)
                if (t.receiptPaths.isNotEmpty()) Icon(Icons.Default.AttachFile, null, tint = Color.LightGray, modifier = Modifier.size(14.dp))
            } }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
        } }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TransactionDialog(t: Transaction? = null, onDismiss: () -> Unit, onSave: (Transaction) -> Unit, onDelete: (Transaction) -> Unit, onCam: ((Uri) -> Unit) -> Unit, onGal: ((Uri) -> Unit) -> Unit, isVatRegistered: Boolean, vm: VeromuistioViewModel) {
    val allTxs by vm.txs.collectAsState()
    var type by remember { mutableStateOf(t?.type ?: TransactionType.INCOME) }
    var desc by remember { mutableStateOf(t?.description ?: "") }; var amount by remember { mutableStateOf(t?.amount?.toString() ?: "") }
    var cat by remember { mutableStateOf(t?.category ?: "Pystykauppa") }
    var vat by remember { mutableDoubleStateOf(t?.vatRate ?: (if (isVatRegistered) 25.5 else 0.0)) }
    var paths by remember { mutableStateOf(t?.receiptPaths ?: emptyList()) }; var purp by remember { mutableStateOf(t?.purpose ?: "") }
    var dest by remember { mutableStateOf(t?.destination ?: "") }
    var withTax by remember { mutableStateOf(t?.withholdingTax?.toString() ?: "") }
    var hours by remember { mutableStateOf(t?.workHours?.toString() ?: "") }
    var mil by remember { mutableStateOf(t?.mileage?.toString() ?: "") }; var rate by remember { mutableStateOf(t?.ratePerKm?.toString() ?: "0.27") }
    val df = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()); var date by remember { mutableStateOf(t?.date ?: df.format(Date())) }; var showDP by remember { mutableStateOf(false) }; var zoomImg by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }
    var showTransactionDeleteConfirm by remember { mutableStateOf(false) }

    var isDeliverySale by remember { mutableStateOf(t?.isDeliverySale ?: false) }
    var m3 by remember { mutableStateOf(t?.cubicMeters?.toString() ?: "") }
    var unitValue by remember { mutableStateOf(t?.workValuePerUnit?.toString() ?: "13.00") }

    if (showDP) {
        val state = rememberDatePickerState(initialSelectedDateMillis = try { df.parse(date)?.time } catch (e: Exception) { null } ?: System.currentTimeMillis())
        DatePickerDialog(onDismissRequest = { showDP = false }, confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { date = df.format(Date(it)) }; showDP = false }) { Text("OK") } }) { DatePicker(state = state) }
    }

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Poista kuva") },
            text = { Text("Haluatko varmasti poistaa tämän kuvan?") },
            confirmButton = { TextButton({ paths = paths - showDeleteConfirm!!; showDeleteConfirm = null }) { Text("POISTA", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ showDeleteConfirm = null }) { Text("PERUUTA") } }
        )
    }

    if (showTransactionDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showTransactionDeleteConfirm = false },
            title = { Text("Poista tosite") },
            text = { Text("Haluatko varmasti poistaa tämän tositteen?") },
            confirmButton = { TextButton({ t?.let { onDelete(it) }; showTransactionDeleteConfirm = false }) { Text("POISTA", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ showTransactionDeleteConfirm = false }) { Text("PERUUTA") } }
        )
    }

    BasicAlertDialog(onDismissRequest = onDismiss, modifier = Modifier.fillMaxSize(), properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column {
            CenterAlignedTopAppBar(title = { Text(if (t == null) "Uusi tapahtuma" else "Muokkaa") }, navigationIcon = { IconButton(onDismiss) { Icon(Icons.Default.Close, null) } }, actions = {
                if (t != null) IconButton({ showTransactionDeleteConfirm = true }) { Icon(Icons.Default.Delete, null, tint = ExpenseRed) }
                TextButton({
                    val n = if (type == TransactionType.MILEAGE) {
                        val m = mil.replace(',', '.').toDoubleOrNull() ?: 0.0
                        val r = rate.replace(',', '.').toDoubleOrNull() ?: 0.0
                        val h = hours.replace(',', '.').toDoubleOrNull()
                        Transaction(id = t?.id ?: 0, description = "Ajo: $purp", amount = m * r, type = type, category = "Matkakulut", date = date, vatRate = 0.0, mileage = m, ratePerKm = r, purpose = purp, destination = dest.takeIf { it.isNotBlank() }, workHours = h)
                    } else Transaction(
                        id = t?.id ?: 0, 
                        description = desc, 
                        amount = amount.replace(',', '.').toDoubleOrNull() ?: 0.0, 
                        type = type, 
                        category = cat, 
                        date = date, 
                        vatRate = if (isVatRegistered) vat else 0.0, 
                        receiptPaths = paths, 
                        withholdingTax = if(cat == "Pystykauppa" || cat == "Hankintakauppa") withTax.replace(',','.').toDoubleOrNull() else null,
                        isDeliverySale = isDeliverySale,
                        cubicMeters = if(isDeliverySale) m3.replace(',','.').toDoubleOrNull() else null,
                        workValuePerUnit = if(isDeliverySale) unitValue.replace(',','.').toDoubleOrNull() else null
                    )
                    onSave(n)
                }) { Text("TALLENNA", fontWeight = FontWeight.Bold) }
            })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(type == TransactionType.INCOME, { type = TransactionType.INCOME }, SegmentedButtonDefaults.itemShape(0, 3)) { Text("Tulo") }
                    SegmentedButton(type == TransactionType.EXPENSE, { type = TransactionType.EXPENSE }, SegmentedButtonDefaults.itemShape(1, 3)) { Text("Meno") }
                    SegmentedButton(type == TransactionType.MILEAGE, { type = TransactionType.MILEAGE }, SegmentedButtonDefaults.itemShape(2, 3)) { Text("Ajo") }
                }
                
                if (type == TransactionType.INCOME || type == TransactionType.EXPENSE) {
                    Text("${if(type == TransactionType.INCOME) "Tulon" else "Menon"} tyyppi", style = MaterialTheme.typography.labelLarge)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val categories = if (type == TransactionType.INCOME) {
                            listOf("Pystykauppa", "Hankintakauppa", "Polttopuut", "Metsätalouden tuet", "Vakuutuskorvaukset")
                        } else {
                            listOf("Vakuutukset", "Koneet ja kalusto", "Rakennukset", "Ojat ja tiet", "Muut kulut")
                        }
                        categories.forEach { c -> 
                            FilterChip(
                                selected = cat == c, 
                                onClick = { 
                                    cat = c
                                    if (type == TransactionType.INCOME) {
                                        isDeliverySale = (c == "Hankintakauppa")
                                    }
                                }, 
                                label = { Text(c) }
                            ) 
                        }
                    }

                    TextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("Summa (€)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    
                    if (isDeliverySale && type == TransactionType.INCOME) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextField(m3, { m3 = it }, Modifier.weight(1f), label = { Text("Määrä (m³)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                            TextField(unitValue, { unitValue = it }, Modifier.weight(1f), label = { Text("Työarvo (€/m³)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        }
                        
                        val m3Val = m3.replace(',', '.').toDoubleOrNull() ?: 0.0
                        val uvVal = unitValue.replace(',', '.').toDoubleOrNull() ?: 0.0
                        val workVal = m3Val * uvVal
                        val totalVal = amount.replace(',', '.').toDoubleOrNull() ?: 0.0
                        
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Puun myyntitulo: ${String.format("%.2f", totalVal - workVal)} €", style = MaterialTheme.typography.bodyMedium)
                                Text("Hankintatyön arvo: ${String.format("%.2f", workVal)} €", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                
                                val yearStr = date.split(".").last()
                                val otherTxsM3 = allTxs.filter { it.id != (t?.id ?: 0) && it.isDeliverySale && it.date.endsWith(".$yearStr") }.sumOf { it.cubicMeters ?: 0.0 }
                                val yearlyM3 = otherTxsM3 + m3Val
                                    
                                if (yearlyM3 > 125.0) {
                                    Text("Varoitus: 125 m³ raja ylittyy (${String.format("%.1f", yearlyM3)} m³)", color = ExpenseRed, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    if (cat == "Pystykauppa" || cat == "Hankintakauppa") {
                        TextField(withTax, { withTax = it }, Modifier.fillMaxWidth(), label = { Text("Ennakonpidätys (€)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }

                    TextField(desc, { desc = it }, Modifier.fillMaxWidth(), label = { Text("Kuvaus") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                    if (isVatRegistered) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            Text("ALV: ", style = MaterialTheme.typography.labelLarge)
                            listOf(0.0, 10.0, 14.0, 25.5).forEach { v ->
                                FilterChip(
                                    selected = vat == v,
                                    onClick = { vat = v },
                                    label = { Text("${if (v == 25.5) "25.5" else v.toInt().toString()}%") },
                                    modifier = Modifier.padding(horizontal = 2.dp)
                                )
                            }
                        }
                    }
                } else {
                    TextField(purp, { purp = it }, Modifier.fillMaxWidth(), label = { Text("Ajon tarkoitus") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                    TextField(dest, { dest = it }, Modifier.fillMaxWidth(), label = { Text("Kohde") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val mVal = mil.replace(',', '.')
                        val rVal = rate.replace(',', '.')
                        TextField(mil, { mil = it }, Modifier.weight(1f), label = { Text("Kilometrit") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        TextField(rate, { rate = it }, Modifier.weight(1f), label = { Text("€/km") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                    TextField(hours, { hours = it }, Modifier.fillMaxWidth(), label = { Text("Työtunnit") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Text("Yhteensä: ${String.format("%.2f", (mil.replace(',', '.').toDoubleOrNull() ?: 0.0) * (rate.replace(',', '.').toDoubleOrNull() ?: 0.0))} €", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
                OutlinedCard(onClick = { showDP = true }, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Päivämäärä"); Text(date, fontWeight = FontWeight.Bold) } }
                if (type != TransactionType.MILEAGE) {
                    Text("Kuitit (${paths.size})", fontWeight = FontWeight.Bold)
                    if (paths.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(paths) { p ->
                                Box(Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)).clickable { zoomImg = p }) {
                                    AsyncImage(p, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    IconButton({ showDeleteConfirm = p }, Modifier.align(Alignment.TopEnd).background(Color.Black.copy(0.5f), CircleShape).size(30.dp)) { Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ onCam { paths = paths + it.toString() } }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text("Kamera") }
                        Button({ onGal { paths = paths + it.toString() } }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Icon(Icons.Default.Collections, null); Spacer(Modifier.width(8.dp)); Text("Galleria") }
                    }
                }
                Spacer(Modifier.height(100.dp))
            }
        }
        if (zoomImg != null) BasicAlertDialog(onDismissRequest = { zoomImg = null }) { AsyncImage(zoomImg, null, Modifier.fillMaxWidth().clickable { zoomImg = null }, contentScale = ContentScale.FillWidth) }
    } }
}


