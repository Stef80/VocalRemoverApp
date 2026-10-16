# Widget flottante per la registrazione da un'altra app — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Aggiungere un widget flottante ("bubble", stile chat heads) che resta visibile sopra qualunque altra app e permette di scegliere l'app sorgente, avviare/fermare la registrazione dell'audio di sistema e tornare automaticamente a VocalRemoverApp al termine, senza modificare la pipeline di cattura/elaborazione già esistente.

**Architecture:** Nuovo `BubbleOverlayService` (Foreground Service con `WindowManager`/`TYPE_APPLICATION_OVERLAY`) disegna e orchestras il bubble; una nuova `MediaProjectionConsentActivity` trasparente funge da trampolino per il consenso di sistema (necessario da un'Activity). **Tutto il codice del widget (View/Service/Activity/stato) vive in `:ui`** (modulo applicazione, che possiede il launcher e tutte le View dell'app): `:engine` resta libreria di sola logica (decoding, inferenza, cattura) e non riceve più nuova logica, perché la sua legacy `MainActivity` XML/ViewBinding verrà smantellata a fine migrazione verso Compose. Il bubble riusa **senza modifiche** `SystemAudioCaptureService` (cattura) tramite le sue action/broadcast già esistenti, e tre classi di `:engine` promosse da `internal` a pubbliche per essere leggibili da `:ui` (`CaptureTerminalKind`, `CaptureTerminalState`, `CaptureTerminalResult`, `CaptureTerminalResultStore`). La logica di selezione/ordinamento del pacchetto sorgente, oggi duplicata solo nella legacy `MainActivity`, viene estratta in due classi condivise (`CapturePackageSuggestions` pura e `CapturePackageDirectory` con accesso Android) che restano in `:engine` perché sono un refactor della legacy `MainActivity` stessa, non nuova logica, e sono consumate sia dalla legacy UI sia dal bubble in `:ui`.

**Tech Stack:** Kotlin, Android Views + ViewBinding (abilitato anche in `:ui` accanto a Compose, solo per il bubble) nel modulo `:ui`, WindowManager overlay, MediaProjectionManager, JUnit4 per i test JVM puri.

## Global Constraints

- `minSdk = 26`, `compileSdk = 37`, `targetSdk = 36` per entrambi i moduli (`engine/build.gradle.kts:7-11`, `ui/build.gradle.kts:10-19`).
- `:engine` non deve mai dipendere da `:ui`; `:ui` dipende da `:engine` (direzione già permessa). **Regola di progetto:** nessuna nuova logica va aggiunta alla legacy `MainActivity` di `:engine` (verrà smantellata a fine migrazione) — tutto il codice nuovo del widget flottante (View, Service, Activity) vive in `:ui`. Solo le classi di `:engine` necessarie al bubble (`CapturePackageSuggestions`, `CapturePackageDirectory`, `SystemAudioCaptureService`, `CaptureTerminalResultStore`/`CaptureTerminalKind`) sono pubbliche e consumate cross-modulo da `:ui`.
- La cattura audio richiede Android 10 (`Build.VERSION_CODES.Q`) o superiore: `SystemAudioCaptureService` è già annotato `@RequiresApi(Build.VERSION_CODES.Q)` e non va toccato.
- Nessuna modifica alla pipeline di cattura/elaborazione esistente (`SystemAudioCaptureService`, `VocalRemover.removeVocals`, `RecordingSaver`, `CaptureTerminalResultStore`): il nuovo codice la orchestras solo dall'esterno tramite le action/broadcast già pubbliche.
- Stringhe utente nel modulo `:engine` (solo `CapturePackageSuggestions`/`CapturePackageDirectory`, refactor della legacy `MainActivity`): stringhe italiane dirette nel codice, come già fatto in `SystemAudioCaptureService.buildNotification()` — non introdurre risorse `strings.xml` in `:engine`.
- Stringhe utente nel modulo `:ui`: il bubble (View/Service/Activity, codice Android View non Compose) usa stringhe italiane dirette nel codice, coerentemente con lo stile Android-View già usato in `:engine` per questo tipo di componenti (es. notifiche dei Service); le schermate Compose di `:ui` continuano a usare risorse in `ui/src/main/res/values/strings.xml` lette con `stringResource`.
- Test JVM puri (JUnit4, nessun Robolectric disponibile nel progetto): solo la logica senza dipendenze Android è automaticamente testabile (`CapturePackageSuggestionsTest` in `:engine`, `BubbleOverlayStateTest` in `:ui`); tutto il resto (View, WindowManager, Service, Activity, permessi di sistema) si verifica manualmente su device reale.

---

## File Structure

```
engine/src/main/java/com/example/vocalremover/
  capture/
    CapturePackageSuggestions.kt      (nuovo — logica pura di ordinamento/parsing, estratta da MainActivity)
    CapturePackageDirectory.kt        (nuovo — accesso PackageManager/SharedPreferences/manifest, estratto da MainActivity)
    CaptureTerminalResultStore.kt     (modificato — tipi resi pubblici per il consumo da :ui)
  MainActivity.kt                     (modificato — usa le classi estratte sopra, nessun cambio di comportamento)

engine/src/test/java/com/example/vocalremover/
  capture/CapturePackageSuggestionsTest.kt (nuovo)

ui/src/main/java/com/stef/vocalremover/ui/
  ImportSongScreen.kt                 (modificato — CaptureCard aggancia BubbleOverlayService)
  overlay/
    BubbleOverlayState.kt             (nuovo — stato puro + transizioni del bubble)
    OverlayLifecycleOwner.kt          (nuovo — owner lifecycle/saved-state/viewmodel per ComposeView in overlay)
    BubbleOverlayContent.kt           (nuovo — UI Compose del bubble: icona trascinabile + lista app + CTA)
    MediaProjectionConsentActivity.kt (nuovo — trampolino trasparente per il consenso di sistema)
    BubbleOverlayService.kt           (nuovo — Foreground Service che ospita il bubble via WindowManager)

ui/src/main/res/
  values/strings.xml                  (modificato — stringa snackbar permesso overlay)
  values/themes.xml                   (modificato — aggiunto tema trasparente per il trampolino)

ui/src/main/AndroidManifest.xml       (modificato — permesso overlay, nuovo servizio, nuova activity)

ui/src/test/java/com/stef/vocalremover/ui/
  overlay/BubbleOverlayStateTest.kt   (nuovo)

ui/build.gradle.kts                   (modificato — JUnit4 + lifecycle runtime + savedstate per l'overlay Compose)
gradle/libs.versions.toml             (modificato — alias `androidx-savedstate`)
```

---

### Task 1: Estrarre la logica pura di ordinamento/parsing del picker pacchetti

**Files:**
- Create: `engine/src/main/java/com/example/vocalremover/capture/CapturePackageSuggestions.kt`
- Test: `engine/src/test/java/com/example/vocalremover/capture/CapturePackageSuggestionsTest.kt`

**Interfaces:**
- Produces: `CapturePackageSuggestions.orderedDisplayTexts(recentPackages: List<String>, candidates: Map<String, String>): List<String>`, `CapturePackageSuggestions.packageNameFromDisplayText(displayText: String): String` — usate da Task 2 (refactor `MainActivity`) e Task 7 (`BubbleOverlayService`), Task 6 (`BubbleOverlayView`).

- [ ] **Step 1: Scrivere i test (falliranno finché la classe non esiste)**

```kotlin
package com.example.vocalremover.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CapturePackageSuggestionsTest {

    @Test
    fun `recent packages come first in their recency order`() {
        val candidates = mapOf(
            "com.a" to "App A (com.a)",
            "com.b" to "App B (com.b)",
            "com.c" to "App C (com.c)"
        )
        val result = CapturePackageSuggestions.orderedDisplayTexts(
            recentPackages = listOf("com.c", "com.a"),
            candidates = candidates
        )
        assertEquals(listOf("App C (com.c)", "App A (com.a)", "App B (com.b)"), result)
    }

    @Test
    fun `non recent candidates are sorted alphabetically by label`() {
        val candidates = mapOf(
            "com.z" to "Zeta (com.z)",
            "com.a" to "Alpha (com.a)"
        )
        val result = CapturePackageSuggestions.orderedDisplayTexts(
            recentPackages = emptyList(),
            candidates = candidates
        )
        assertEquals(listOf("Alpha (com.a)", "Zeta (com.z)"), result)
    }

    @Test
    fun `recent packages not in candidates are ignored`() {
        val candidates = mapOf("com.a" to "App A (com.a)")
        val result = CapturePackageSuggestions.orderedDisplayTexts(
            recentPackages = listOf("com.uninstalled", "com.a"),
            candidates = candidates
        )
        assertEquals(listOf("App A (com.a)"), result)
    }

    @Test
    fun `packageNameFromDisplayText extracts the package name in parentheses`() {
        assertEquals("com.a", CapturePackageSuggestions.packageNameFromDisplayText("App A (com.a)"))
    }

    @Test
    fun `packageNameFromDisplayText returns the text itself when there are no parentheses`() {
        assertEquals("com.a", CapturePackageSuggestions.packageNameFromDisplayText("com.a"))
    }
}
```

- [ ] **Step 2: Eseguire i test e verificare che falliscano**

Run: `gradle :engine:testCpuDebugUnitTest --tests "com.example.vocalremover.capture.CapturePackageSuggestionsTest"`
Expected: FAIL con "unresolved reference: CapturePackageSuggestions"

- [ ] **Step 3: Creare la classe (logica identica a quella oggi in `MainActivity`, solo estratta)**

```kotlin
package com.example.vocalremover.capture

/**
 * Logica pura (nessuna dipendenza Android) per ordinare e decodificare i
 * suggerimenti di pacchetto usati dal picker delle app sorgente, condivisa
 * tra la vecchia schermata XML (`MainActivity`) e il widget flottante
 * (`BubbleOverlayService`), per evitare di duplicare l'ordinamento in due posti.
 */
object CapturePackageSuggestions {

    /**
     * Ordina i pacchetti candidati mettendo prima i più recenti (nell'ordine
     * in cui sono stati usati), poi il resto in ordine alfabetico per etichetta.
     *
     * @param recentPackages nomi di pacchetto usati di recente, dal più recente al meno recente
     * @param candidates mappa packageName -> testo da mostrare nel picker
     *        (es. "YouTube (com.google.android.youtube)", o solo il packageName
     *        se l'etichetta coincide col nome del pacchetto)
     */
    fun orderedDisplayTexts(
        recentPackages: List<String>,
        candidates: Map<String, String>
    ): List<String> =
        recentPackages
            .filter { it in candidates }
            .plus(
                candidates.keys
                    .filter { it !in recentPackages }
                    .sortedBy { candidates[it]?.lowercase() ?: it.lowercase() }
            )
            .distinct()
            .mapNotNull { candidates[it] }

    /**
     * Estrae il nome del pacchetto dal testo mostrato nel picker. Se il testo
     * contiene "(packageName)" lo estrae da lì (etichetta diversa dal
     * packageName), altrimenti il testo stesso è già il nome del pacchetto.
     */
    fun packageNameFromDisplayText(displayText: String): String =
        displayText.substringAfterLast("(").trimEnd(')')
}
```

- [ ] **Step 4: Eseguire i test e verificare che passino**

Run: `gradle :engine:testCpuDebugUnitTest --tests "com.example.vocalremover.capture.CapturePackageSuggestionsTest"`
Expected: PASS (5 test)

- [ ] **Step 5: Commit**

```bash
git add engine/src/main/java/com/example/vocalremover/capture/CapturePackageSuggestions.kt \
        engine/src/test/java/com/example/vocalremover/capture/CapturePackageSuggestionsTest.kt
git commit -m "feat(capture): extract pure package-suggestion ordering logic"
```

---

### Task 2: Estrarre l'accesso Android (PackageManager/SharedPreferences/manifest) e rifattorizzare `MainActivity`

**Files:**
- Create: `engine/src/main/java/com/example/vocalremover/capture/CapturePackageDirectory.kt`
- Modify: `engine/src/main/java/com/example/vocalremover/MainActivity.kt:309-358` (metodo `setupCapturePackageSuggestions`), `MainActivity.kt` (metodi `readCaptureCandidatePackagesFromManifest`, `readRecentCapturePackages`, `rememberCapturePackage`, `resolveTargetUid` — rimossi, sostituiti da chiamate a `CapturePackageDirectory`)

**Interfaces:**
- Consumes: `CapturePackageSuggestions.orderedDisplayTexts(...)`, `CapturePackageSuggestions.packageNameFromDisplayText(...)` (Task 1).
- Produces: `CapturePackageDirectory(context: Context)` con `readRecentPackages(): List<String>`, `rememberPackage(packageName: String)`, `readCandidatePackagesFromManifest(): List<String>`, `candidateDisplayNames(): Map<String, String>`, `resolveTargetUid(packageName: String): Int?` — usati da Task 7 (`BubbleOverlayService`).

Nessun test automatico per questo task (accesso diretto a `Context`/`PackageManager`/`SharedPreferences`, non testabile in JVM puro senza Robolectric, non presente nel progetto): la verifica è manuale, vedi Step 3.

- [ ] **Step 1: Creare `CapturePackageDirectory` con lo stesso comportamento del codice oggi in `MainActivity`**

```kotlin
package com.example.vocalremover.capture

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.xmlpull.v1.XmlPullParser

/**
 * Legge/scrive i pacchetti candidati e recenti per il picker dell'app sorgente
 * da catturare. Condivisa tra la vecchia schermata XML (`MainActivity`) e il
 * widget flottante (`BubbleOverlayService`) per non duplicare l'accesso a
 * PackageManager/SharedPreferences/AndroidManifest in due posti.
 */
class CapturePackageDirectory(private val context: Context) {

    companion object {
        private const val PREFS_CAPTURE_PACKAGE_PICKER = "capture_package_picker"
        private const val KEY_RECENT_CAPTURE_PACKAGES = "recent_capture_packages"
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        private const val MAX_RECENT = 10
    }

    /** Pacchetti usati di recente per la cattura, dal più recente al meno recente. */
    fun readRecentPackages(): List<String> {
        val prefs = context.getSharedPreferences(PREFS_CAPTURE_PACKAGE_PICKER, Context.MODE_PRIVATE)
        return prefs.getString(KEY_RECENT_CAPTURE_PACKAGES, "")
            ?.split('|')
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: emptyList()
    }

    fun rememberPackage(packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_CAPTURE_PACKAGE_PICKER, Context.MODE_PRIVATE)
        val recent = readRecentPackages().filter { it != normalized }.toMutableList()
        recent.add(0, normalized)
        prefs.edit()
            .putString(KEY_RECENT_CAPTURE_PACKAGES, recent.take(MAX_RECENT).joinToString("|"))
            .apply()
    }

    /**
     * Legge i pacchetti dichiarati in <queries><package android:name="..."/></queries>
     * direttamente dall'AndroidManifest.xml compilato: è l'unica fonte di verità,
     * non esiste una lista duplicata in Kotlin. Se il parsing fallisce, ritorna vuoto.
     */
    fun readCandidatePackagesFromManifest(): List<String> {
        var parser: android.content.res.XmlResourceParser? = null
        return try {
            parser = context.assets.openXmlResourceParser("AndroidManifest.xml")
            val packages = mutableListOf<String>()
            var insideQueries = false
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "queries" -> insideQueries = true
                        "package" -> if (insideQueries) {
                            parser.getAttributeValue(ANDROID_NAMESPACE, "name")?.let(packages::add)
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "queries") {
                        insideQueries = false
                    }
                }
                eventType = parser.next()
            }
            packages
        } catch (_: Exception) {
            emptyList()
        } finally {
            parser?.close()
        }
    }

    /** Pacchetti candidati installati e abilitati, con l'etichetta da mostrare nel picker. */
    fun candidateDisplayNames(): Map<String, String> {
        val packageManager = context.packageManager
        return readCandidatePackagesFromManifest()
            .mapNotNull { packageName ->
                val appInfo = try {
                    packageManager.getApplicationInfo(packageName, ApplicationInfo.FLAG_INSTALLED)
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
                appInfo?.takeIf { it.enabled }?.let { packageName to it }
            }
            .associate { (packageName, appInfo) ->
                val label = packageManager.getApplicationLabel(appInfo)?.toString()?.trim()
                val displayText = when {
                    label.isNullOrBlank() -> packageName
                    packageName == label -> packageName
                    else -> "$label ($packageName)"
                }
                packageName to displayText
            }
    }

    fun resolveTargetUid(packageName: String): Int? =
        try {
            context.packageManager.getApplicationInfo(packageName, ApplicationInfo.FLAG_INSTALLED).uid
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
}
```

- [ ] **Step 2: Rifattorizzare `MainActivity.kt` per usare `CapturePackageDirectory` e `CapturePackageSuggestions`, rimuovendo la logica duplicata**

Aggiungere in cima al file (sezione import):

```kotlin
import com.example.vocalremover.capture.CapturePackageDirectory
import com.example.vocalremover.capture.CapturePackageSuggestions
```

Aggiungere una proprietà lazy accanto alle altre proprietà di `MainActivity` (es. vicino a `private lateinit var vocalRemover: VocalRemover`):

```kotlin
private val capturePackageDirectory by lazy { CapturePackageDirectory(this) }
```

Sostituire l'intero corpo di `setupCapturePackageSuggestions()` (oggi `MainActivity.kt:309-337`):

```kotlin
    private fun setupCapturePackageSuggestions() {
        val orderedSuggestions = CapturePackageSuggestions.orderedDisplayTexts(
            capturePackageDirectory.readRecentPackages(),
            capturePackageDirectory.candidateDisplayNames()
        )

        val autoCompleteView = binding.etCapturePackageName as AutoCompleteTextView
        val adapter = ContainsFilterAdapter(this, orderedSuggestions, autoCompleteView)
        autoCompleteView.threshold = 0
        autoCompleteView.setAdapter(adapter)
        autoCompleteView.setOnItemClickListener { _, _, position, _ ->
            val selectedText = adapter.getItem(position)?.toString() ?: return@setOnItemClickListener
            val packageName = CapturePackageSuggestions.packageNameFromDisplayText(selectedText)
            if (packageName.isNotBlank()) {
                autoCompleteView.setText(packageName)
                autoCompleteView.setSelection(packageName.length)
                capturePackageDirectory.rememberPackage(packageName)
            }
        }
        autoCompleteView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showCapturePackageDropdown(autoCompleteView)
        }
        autoCompleteView.setOnClickListener { showCapturePackageDropdown(autoCompleteView) }
    }
```

Rimuovere interamente i metodi privati `readCaptureCandidatePackagesFromManifest()`, `readRecentCapturePackages()` e `rememberCapturePackage(packageName: String)` (oggi dopo `showCapturePackageDropdown`, prima di `setupPlayerCallbacks`): il loro comportamento ora vive in `CapturePackageDirectory`.

Sostituire ogni chiamata rimasta a `rememberCapturePackage(packageName)` dentro `startCapture()` con `capturePackageDirectory.rememberPackage(packageName)`.

Sostituire il corpo di `resolveTargetUid(packageName: String): Int?` con una delega:

```kotlin
    private fun resolveTargetUid(packageName: String): Int? =
        capturePackageDirectory.resolveTargetUid(packageName)
```

Rimuovere le costanti ormai inutilizzate nel `companion object` di `MainActivity` (`PREFS_CAPTURE_PACKAGE_PICKER`, `KEY_RECENT_CAPTURE_PACKAGES`, `ANDROID_NAMESPACE`) se non più referenziate altrove nel file.

- [ ] **Step 3: Verificare manualmente che il comportamento non sia cambiato**

Run: `gradle :engine:compileCpuDebugKotlin :engine:testCpuDebugUnitTest`
Expected: compilazione e test esistenti (incluso `CapturePackageSuggestionsTest`) in PASS.

Poi, su un device/emulatore con Android 10+: `gradle :ui:installCpuDebug`, aprire la vecchia schermata (bottone "Apri vecchia schermata" dalla `PlayerScreen`), toccare il campo pacchetto sorgente e verificare che il menu a tendina mostri gli stessi suggerimenti di prima (pacchetti recenti in cima, poi il resto in ordine alfabetico), che selezionarne uno compili il campo, e che il pulsante "Avvia" funzioni come prima.

- [ ] **Step 4: Commit**

```bash
git add engine/src/main/java/com/example/vocalremover/capture/CapturePackageDirectory.kt \
        engine/src/main/java/com/example/vocalremover/MainActivity.kt
git commit -m "refactor(capture): extract Android-backed package directory, reuse in MainActivity"
```

---

### Task 3: Stato puro del bubble e transizioni

**Files:**
- Create: `ui/src/main/java/com/stef/vocalremover/ui/overlay/BubbleOverlayState.kt`
- Test: `ui/src/test/java/com/stef/vocalremover/ui/overlay/BubbleOverlayStateTest.kt`

**Interfaces:**
- Produces: `BubbleOverlayPhase` (enum: `IDLE, APP_SELECTED, AWAITING_CONSENT, RECORDING, STOPPING, ERROR`), `BubbleOverlayState(phase: BubbleOverlayPhase = IDLE, packageName: String? = null, targetUid: Int? = null, errorMessage: String? = null)`, `BubbleOverlayTransitions.selectApp(current, packageName, targetUid): BubbleOverlayState?`, `.requestConsent(current): BubbleOverlayState?`, `.startRecording(current): BubbleOverlayState?`, `.requestStop(current): BubbleOverlayState?`, `.stopped(current): BubbleOverlayState?`, `.failed(current, message): BubbleOverlayState`, `.dismissError(current): BubbleOverlayState?` — usati da Task 6 (`BubbleOverlayContent`) e Task 7 (`BubbleOverlayService`).

- [ ] **Step 1: Scrivere i test (falliranno finché la classe non esiste)**

```kotlin
package com.stef.vocalremover.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BubbleOverlayStateTest {

    @Test
    fun `selecting an app from idle moves to app selected`() {
        val next = BubbleOverlayTransitions.selectApp(
            BubbleOverlayState(BubbleOverlayPhase.IDLE),
            packageName = "com.example.target",
            targetUid = 1234
        )
        assertEquals(
            BubbleOverlayState(BubbleOverlayPhase.APP_SELECTED, "com.example.target", 1234),
            next
        )
    }

    @Test
    fun `selecting an app while recording is rejected`() {
        val current = BubbleOverlayState(BubbleOverlayPhase.RECORDING, "com.example.target", 1234)
        assertNull(BubbleOverlayTransitions.selectApp(current, "com.other", 5678))
    }

    @Test
    fun `requesting consent only works from app selected`() {
        val selected = BubbleOverlayState(BubbleOverlayPhase.APP_SELECTED, "com.a", 1)
        assertEquals(
            BubbleOverlayState(BubbleOverlayPhase.AWAITING_CONSENT, "com.a", 1),
            BubbleOverlayTransitions.requestConsent(selected)
        )
        assertNull(BubbleOverlayTransitions.requestConsent(BubbleOverlayState(BubbleOverlayPhase.IDLE)))
    }

    @Test
    fun `starting the recording only works while awaiting consent`() {
        val awaiting = BubbleOverlayState(BubbleOverlayPhase.AWAITING_CONSENT, "com.a", 1)
        assertEquals(
            BubbleOverlayState(BubbleOverlayPhase.RECORDING, "com.a", 1),
            BubbleOverlayTransitions.startRecording(awaiting)
        )
        assertNull(
            BubbleOverlayTransitions.startRecording(
                BubbleOverlayState(BubbleOverlayPhase.APP_SELECTED, "com.a", 1)
            )
        )
    }

    @Test
    fun `requesting stop only works while recording`() {
        val recording = BubbleOverlayState(BubbleOverlayPhase.RECORDING, "com.a", 1)
        assertEquals(
            BubbleOverlayState(BubbleOverlayPhase.STOPPING, "com.a", 1),
            BubbleOverlayTransitions.requestStop(recording)
        )
        assertNull(BubbleOverlayTransitions.requestStop(BubbleOverlayState(BubbleOverlayPhase.IDLE)))
    }

    @Test
    fun `stopped resets to idle only from stopping`() {
        val stopping = BubbleOverlayState(BubbleOverlayPhase.STOPPING, "com.a", 1)
        assertEquals(
            BubbleOverlayState(BubbleOverlayPhase.IDLE),
            BubbleOverlayTransitions.stopped(stopping)
        )
        assertNull(
            BubbleOverlayTransitions.stopped(BubbleOverlayState(BubbleOverlayPhase.RECORDING, "com.a", 1))
        )
    }

    @Test
    fun `failed can happen from any phase and carries a message`() {
        val fromRecording = BubbleOverlayTransitions.failed(
            BubbleOverlayState(BubbleOverlayPhase.RECORDING, "com.a", 1),
            "Errore di cattura"
        )
        assertEquals(
            BubbleOverlayState(BubbleOverlayPhase.ERROR, "com.a", 1, "Errore di cattura"),
            fromRecording
        )
    }

    @Test
    fun `dismissing the error resets to idle only from error`() {
        val error = BubbleOverlayState(BubbleOverlayPhase.ERROR, errorMessage = "boom")
        assertEquals(BubbleOverlayState(BubbleOverlayPhase.IDLE), BubbleOverlayTransitions.dismissError(error))
        assertNull(BubbleOverlayTransitions.dismissError(BubbleOverlayState(BubbleOverlayPhase.IDLE)))
    }
}
```

- [ ] **Step 2: Eseguire i test e verificare che falliscano**

Run: `gradle :ui:testCpuDebugUnitTest --tests "com.stef.vocalremover.ui.overlay.BubbleOverlayStateTest"`
Expected: FAIL con "unresolved reference: BubbleOverlayState"

- [ ] **Step 3: Creare lo stato e le transizioni**

```kotlin
package com.stef.vocalremover.ui.overlay

/**
 * Stato puro (nessuna dipendenza Android) del widget flottante di registrazione.
 * Le transizioni sono funzioni pure: non effettuano IO, non toccano WindowManager
 * né i servizi di cattura. `BubbleOverlayService` le usa per decidere cosa fare
 * e `BubbleOverlayContent` per decidere cosa mostrare.
 */
enum class BubbleOverlayPhase {
    IDLE,
    APP_SELECTED,
    AWAITING_CONSENT,
    RECORDING,
    STOPPING,
    ERROR
}

data class BubbleOverlayState(
    val phase: BubbleOverlayPhase = BubbleOverlayPhase.IDLE,
    val packageName: String? = null,
    val targetUid: Int? = null,
    val errorMessage: String? = null
)

/**
 * Transizioni valide tra stati del bubble. Ogni funzione ritorna il nuovo stato,
 * oppure null se la transizione non è valida nello stato corrente (il chiamante
 * ignora l'evento invece di propagare un'eccezione: eventi fuori ordine possono
 * arrivare da broadcast asincroni del servizio di cattura).
 */
object BubbleOverlayTransitions {

    fun selectApp(
        current: BubbleOverlayState,
        packageName: String,
        targetUid: Int
    ): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.IDLE || current.phase == BubbleOverlayPhase.ERROR) {
            BubbleOverlayState(BubbleOverlayPhase.APP_SELECTED, packageName, targetUid)
        } else {
            null
        }

    fun requestConsent(current: BubbleOverlayState): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.APP_SELECTED) {
            current.copy(phase = BubbleOverlayPhase.AWAITING_CONSENT)
        } else {
            null
        }

    fun startRecording(current: BubbleOverlayState): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.AWAITING_CONSENT) {
            current.copy(phase = BubbleOverlayPhase.RECORDING)
        } else {
            null
        }

    fun requestStop(current: BubbleOverlayState): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.RECORDING) {
            current.copy(phase = BubbleOverlayPhase.STOPPING)
        } else {
            null
        }

    fun stopped(current: BubbleOverlayState): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.STOPPING) {
            BubbleOverlayState(BubbleOverlayPhase.IDLE)
        } else {
            null
        }

    fun failed(current: BubbleOverlayState, message: String): BubbleOverlayState =
        current.copy(phase = BubbleOverlayPhase.ERROR, errorMessage = message)

    fun dismissError(current: BubbleOverlayState): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.ERROR) {
            BubbleOverlayState(BubbleOverlayPhase.IDLE)
        } else {
            null
        }
}
```

- [ ] **Step 4: Eseguire i test e verificare che passino**

Run: `gradle :ui:testCpuDebugUnitTest --tests "com.stef.vocalremover.ui.overlay.BubbleOverlayStateTest"`
Expected: PASS (8 test)

- [ ] **Step 5: Commit**

```bash
git add ui/src/main/java/com/stef/vocalremover/ui/overlay/BubbleOverlayState.kt \
        ui/src/test/java/com/stef/vocalremover/ui/overlay/BubbleOverlayStateTest.kt
git commit -m "feat(ui-overlay): add pure bubble state machine"
```


---

### Task 4: Tema trasparente per il trampolino di consenso

**Files:**
- Modify: `ui/src/main/res/values/themes.xml`

**Interfaces:**
- Produces: stile `@style/Theme.VocalRemover.Transparent` — usato da Task 5 (manifest di `MediaProjectionConsentActivity`).

- [ ] **Step 1: Aggiungere il tema trasparente accanto a quello esistente**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.VocalRemover.Launcher" parent="Theme.MaterialComponents.DayNight.NoActionBar">
        <item name="android:windowBackground">#19263B</item>
        <item name="android:statusBarColor">#19263B</item>
        <item name="android:navigationBarColor">#19263B</item>
        <item name="android:windowLightStatusBar">false</item>
    </style>

    <!-- Usato solo da MediaProjectionConsentActivity: un'Activity "trampolino"
         (ComponentActivity, nessuna UI Compose propria) che deve solo mostrare
         il dialog di sistema del consenso MediaProjection sopra il bubble
         flottante. ComponentActivity non richiede un tema discendente di
         AppCompat: si parte dal tema di sistema per la trasparenza. -->
    <style name="Theme.VocalRemover.Transparent" parent="android:Theme.Translucent.NoTitleBar">
        <item name="android:windowIsTranslucent">true</item>
        <item name="android:windowBackground">@android:color/transparent</item>
        <item name="android:windowAnimationStyle">@null</item>
    </style>
</resources>
```

- [ ] **Step 2: Verificare che l'app risolva davvero il tema in fase di resource linking**

Run: `gradle :ui:processCpuDebugResources :ui:compileCpuDebugKotlin`
Expected: BUILD SUCCESSFUL (qui si valida davvero il parent del tema, non solo la compilazione Kotlin)

- [ ] **Step 3: Commit**

```bash
git add ui/src/main/res/values/themes.xml
git commit -m "feat(ui-overlay): add transparent theme for the consent trampoline activity"
```


---

### Task 5: Trampolino `MediaProjectionConsentActivity`

**Files:**
- Create: `ui/src/main/java/com/stef/vocalremover/ui/overlay/MediaProjectionConsentActivity.kt`
- Modify: `ui/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `Theme.VocalRemover.Transparent` (Task 4); riusa invariati `SystemAudioCaptureService.EXTRA_RESULT_CODE/EXTRA_RESULT_DATA/EXTRA_TARGET_UID` e l'avvio del servizio (stesso meccanismo già in `MainActivity.mediaProjectionLauncher`).
- Produces: `MediaProjectionConsentActivity.createIntent(context, packageName, targetUid): Intent`, costanti `ACTION_RESULT`, `EXTRA_SUCCESS`, `EXTRA_MESSAGE` — usati da Task 7 (`BubbleOverlayService`).

- [ ] **Step 1: Creare l'Activity trampolino (ComponentActivity, nessuna UI, nessun `setContent`)**

```kotlin
package com.stef.vocalremover.ui.overlay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.vocalremover.capture.SystemAudioCaptureService

/**
 * Activity trasparente ("trampolino"): la sola ragione per cui esiste è che il
 * consenso di sistema `MediaProjectionManager` deve essere richiesto da
 * un'Activity, mentre il widget flottante vive in un Service
 * (`BubbleOverlayService`). Non disegna nulla: chiede i permessi necessari,
 * poi il consenso, avvia direttamente `SystemAudioCaptureService` in caso di
 * successo, notifica il risultato con un broadcast locale e si chiude subito.
 */
class MediaProjectionConsentActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_TARGET_UID = "target_uid"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_MESSAGE = "message"
        const val ACTION_RESULT =
            "com.example.vocalremover.action.MEDIA_PROJECTION_CONSENT_RESULT"

        fun createIntent(context: Context, packageName: String, targetUid: Int): Intent =
            Intent(context, MediaProjectionConsentActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_TARGET_UID, targetUid)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }

    private var targetUid: Int = -1

    private val requestRecordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestNotificationPermissionThenConsent()
        else finishWithFailure("Permesso microfono non concesso")
    }

    private val requestPostNotificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestMediaProjectionConsent()
        else finishWithFailure("Permesso notifiche non concesso: registrazione non avviata")
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            finishWithFailure("Consenso per la cattura audio non concesso")
            return@registerForActivityResult
        }
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, SystemAudioCaptureService::class.java).apply {
                    putExtra(SystemAudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(SystemAudioCaptureService.EXTRA_RESULT_DATA, result.data)
                    putExtra(SystemAudioCaptureService.EXTRA_TARGET_UID, targetUid)
                }
            )
            finishWithSuccess()
        } catch (error: Exception) {
            finishWithFailure(error.message ?: "Impossibile avviare il servizio di cattura")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetUid = intent.getIntExtra(EXTRA_TARGET_UID, -1)
        if (targetUid < 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            finishWithFailure("Impossibile avviare la cattura audio su questo dispositivo")
            return
        }

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermissionThenConsent()
        } else {
            requestRecordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestNotificationPermissionThenConsent() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPostNotificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestMediaProjectionConsent()
        }
    }

    private fun requestMediaProjectionConsent() {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjectionLauncher.launch(createMediaProjectionIntent(projectionManager))
    }

    private fun createMediaProjectionIntent(manager: MediaProjectionManager): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }

    private fun finishWithSuccess() {
        sendBroadcast(Intent(ACTION_RESULT).setPackage(packageName).putExtra(EXTRA_SUCCESS, true))
        finish()
    }

    private fun finishWithFailure(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        sendBroadcast(
            Intent(ACTION_RESULT)
                .setPackage(packageName)
                .putExtra(EXTRA_SUCCESS, false)
                .putExtra(EXTRA_MESSAGE, message)
        )
        finish()
    }
}
```

- [ ] **Step 2: Dichiarare l'Activity nel manifest di `:ui` con nome fully-qualified**

Aggiungere dentro `<application>` in `ui/src/main/AndroidManifest.xml`, dopo l'entry di `com.stef.vocalremover.ui.MainActivity`:

```xml
        <activity
            android:name="com.stef.vocalremover.ui.overlay.MediaProjectionConsentActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:theme="@style/Theme.VocalRemover.Transparent" />
```

- [ ] **Step 3: Verificare compilazione Kotlin + resource linking del modulo app**

Run: `gradle :ui:compileCpuDebugKotlin :ui:processCpuDebugResources`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add ui/src/main/java/com/stef/vocalremover/ui/overlay/MediaProjectionConsentActivity.kt \
        ui/src/main/AndroidManifest.xml
git commit -m "feat(ui-overlay): add transparent MediaProjection consent trampoline activity"
```


---

### Task 6: UI Compose del bubble (`BubbleOverlayContent`)

**Files:**
- Create: `ui/src/main/java/com/stef/vocalremover/ui/overlay/OverlayLifecycleOwner.kt`
- Create: `ui/src/main/java/com/stef/vocalremover/ui/overlay/BubbleOverlayContent.kt`

**Interfaces:**
- Consumes: `CapturePackageSuggestions.packageNameFromDisplayText(displayText: String): String` (Task 1), `BubbleOverlayState`/`BubbleOverlayPhase` (Task 3).
- Produces: `OverlayLifecycleOwner` con `attach()` / `detach()` (usato da Task 7 per ospitare un `ComposeView` in `WindowManager` fuori da un'Activity), `BubbleOverlayContent(state, suggestions, onDragDelta, onPackagePicked, onStartRecording, onStopRecording)` — usato da Task 7 (`BubbleOverlayService`).

**Nota di scope:** in `:ui` non introduciamo XML, ViewBinding o `View` custom classiche. Il bubble è una `ComposeView` aggiunta a `WindowManager` dal Service; la UI vera e propria è tutta in Compose.

- [ ] **Step 1: Creare il lifecycle owner riusabile per la `ComposeView` dell'overlay**

```kotlin
package com.stef.vocalremover.ui.overlay

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Owner minimale per ospitare una ComposeView dentro una finestra overlay
 * creata da un Service. La view non è agganciata alla gerarchia di un'Activity,
 * quindi dobbiamo fornirle manualmente lifecycle, ViewModelStore e saved state.
 */
class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun attach() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun detach() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}
```

- [ ] **Step 2: Creare il contenuto Compose del bubble (icona trascinabile + pannello espanso)**

```kotlin
package com.stef.vocalremover.ui.overlay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.vocalremover.capture.CapturePackageSuggestions
import com.example.vocalremover.ui.R
import com.stef.vocalremover.ui.theme.VocalRemoverTheme

@Composable
fun BubbleOverlayContent(
    state: BubbleOverlayState,
    suggestions: List<String>,
    onDragDelta: (Float, Float) -> Unit,
    onPackagePicked: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    if (!expanded) {
        Surface(
            modifier = modifier
                .size(64.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDragDelta(dragAmount.x, dragAmount.y)
                    }
                }
                .clickable { expanded = true },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.logo_corallo_blu),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        return
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = statusText(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
                items(suggestions) { displayText ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                enabled = state.phase == BubbleOverlayPhase.IDLE ||
                                    state.phase == BubbleOverlayPhase.APP_SELECTED ||
                                    state.phase == BubbleOverlayPhase.ERROR
                            ) {
                                onPackagePicked(
                                    CapturePackageSuggestions.packageNameFromDisplayText(displayText)
                                )
                                expanded = false
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = {
                    when (state.phase) {
                        BubbleOverlayPhase.APP_SELECTED -> {
                            expanded = false
                            onStartRecording()
                        }
                        BubbleOverlayPhase.RECORDING -> {
                            expanded = false
                            onStopRecording()
                        }
                        else -> Unit
                    }
                },
                enabled = state.phase == BubbleOverlayPhase.APP_SELECTED ||
                    state.phase == BubbleOverlayPhase.RECORDING,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(actionButtonText(state), fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun statusText(state: BubbleOverlayState): String = when (state.phase) {
    BubbleOverlayPhase.IDLE -> "Scegli l'app da registrare"
    BubbleOverlayPhase.APP_SELECTED -> "Pronto a registrare ${state.packageName}"
    BubbleOverlayPhase.AWAITING_CONSENT -> "In attesa di consenso…"
    BubbleOverlayPhase.RECORDING -> "Registrazione in corso…"
    BubbleOverlayPhase.STOPPING -> "Arresto in corso…"
    BubbleOverlayPhase.ERROR -> state.errorMessage ?: "Errore sconosciuto"
}

private fun actionButtonText(state: BubbleOverlayState): String = when (state.phase) {
    BubbleOverlayPhase.RECORDING -> "Ferma e elabora"
    else -> "Avvia registrazione"
}
```

Aggiungere nello stesso file un `@Preview` minimo (wrappato in `VocalRemoverTheme`) per vedere rapidamente la card espansa in Android Studio senza dover ancora implementare il Service.

- [ ] **Step 3: Verificare che il modulo compili senza XML/ViewBinding aggiuntivi**

Run: `gradle :ui:compileCpuDebugKotlin :ui:testCpuDebugUnitTest --tests "com.stef.vocalremover.ui.overlay.BubbleOverlayStateTest"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add ui/src/main/java/com/stef/vocalremover/ui/overlay/OverlayLifecycleOwner.kt \
        ui/src/main/java/com/stef/vocalremover/ui/overlay/BubbleOverlayContent.kt
git commit -m "feat(ui-overlay): add Compose bubble content and overlay lifecycle owner"
```


---

### Task 7: `BubbleOverlayService` — orchestrazione del bubble

**Files:**
- Create: `ui/src/main/java/com/stef/vocalremover/ui/overlay/BubbleOverlayService.kt`
- Modify: `ui/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `CapturePackageDirectory` (Task 2), `CapturePackageSuggestions` (Task 1), `BubbleOverlayState`/`BubbleOverlayPhase`/`BubbleOverlayTransitions` (Task 3), `MediaProjectionConsentActivity.createIntent/ACTION_RESULT/EXTRA_SUCCESS/EXTRA_MESSAGE` (Task 5), `OverlayLifecycleOwner` e `BubbleOverlayContent` (Task 6), `SystemAudioCaptureService.ACTION_STOP/ACTION_CAPTURE_STOPPED/ACTION_CAPTURE_BLOCKED/ACTION_CAPTURE_ERROR/EXTRA_ERROR_MESSAGE` (esistenti, invariati), `CaptureTerminalResultStore`/`CaptureTerminalKind`/`CaptureTerminalState`/`CaptureTerminalResult` (pubblici in `:engine`, invariati nella semantica).
- Produces: `BubbleOverlayService.canShowOverlay(context: Context): Boolean`, `BubbleOverlayService.overlayPermissionSettingsIntent(context: Context): Intent` — usati da Task 8 (`ImportSongScreen`).

- [ ] **Step 1: Creare il servizio usando `ComposeView` + `WindowManager` (nessun inflate XML)**

```kotlin
package com.stef.vocalremover.ui.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.vocalremover.capture.CapturePackageDirectory
import com.example.vocalremover.capture.CapturePackageSuggestions
import com.example.vocalremover.capture.CaptureTerminalKind
import com.example.vocalremover.capture.CaptureTerminalResultStore
import com.example.vocalremover.capture.SystemAudioCaptureService
import com.stef.vocalremover.ui.MainActivity as LauncherMainActivity
import com.stef.vocalremover.ui.theme.VocalRemoverTheme

/**
 * Foreground service che disegna il widget flottante di registrazione sopra
 * le altre app. Orchestration-only: non implementa la cattura audio (delegata
 * interamente a `SystemAudioCaptureService`, invariato) né il consenso
 * MediaProjection (delegato a `MediaProjectionConsentActivity`).
 */
class BubbleOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "bubble_overlay_control"
        private const val NOTIFICATION_ID = 4343
        private const val CLOSE_REQUEST_CODE = 1
        private const val ERROR_DISPLAY_MS = 3_000L
        const val ACTION_CLOSE = "com.example.vocalremover.action.CLOSE_BUBBLE_OVERLAY"

        /** True se il permesso di overlay è già concesso su questo device. */
        fun canShowOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

        /** Intent per chiedere all'utente il permesso di overlay nelle impostazioni di sistema. */
        fun overlayPermissionSettingsIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
    }

    private lateinit var windowManager: WindowManager
    private lateinit var packageDirectory: CapturePackageDirectory
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var composeView: ComposeView? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val resultStore by lazy { CaptureTerminalResultStore(this) }

    private var state by mutableStateOf(BubbleOverlayState())
    private var suggestions by mutableStateOf(emptyList<String>())

    private val captureResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                SystemAudioCaptureService.ACTION_CAPTURE_STOPPED -> onCaptureStopped()
                SystemAudioCaptureService.ACTION_CAPTURE_BLOCKED ->
                    onCaptureFailed("La sorgente non consente la cattura dell'audio.")
                SystemAudioCaptureService.ACTION_CAPTURE_ERROR ->
                    onCaptureFailed(
                        intent.getStringExtra(SystemAudioCaptureService.EXTRA_ERROR_MESSAGE)
                            ?: "Errore durante la cattura audio."
                    )
            }
        }
    }

    private val consentResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(MediaProjectionConsentActivity.EXTRA_SUCCESS, false)
            if (success) {
                transition { BubbleOverlayTransitions.startRecording(it) }
            } else {
                onCaptureFailed(
                    intent.getStringExtra(MediaProjectionConsentActivity.EXTRA_MESSAGE)
                        ?: "Consenso per la cattura audio non concesso."
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        packageDirectory = CapturePackageDirectory(this)
        suggestions = currentSuggestions()
        startForeground(NOTIFICATION_ID, buildNotification())
        ContextCompat.registerReceiver(
            this,
            captureResultReceiver,
            IntentFilter().apply {
                addAction(SystemAudioCaptureService.ACTION_CAPTURE_STOPPED)
                addAction(SystemAudioCaptureService.ACTION_CAPTURE_BLOCKED)
                addAction(SystemAudioCaptureService.ACTION_CAPTURE_ERROR)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            consentResultReceiver,
            IntentFilter(MediaProjectionConsentActivity.ACTION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(captureResultReceiver) }
        runCatching { unregisterReceiver(consentResultReceiver) }
        composeView?.disposeComposition()
        composeView?.let { runCatching { windowManager.removeView(it) } }
        overlayLifecycleOwner?.detach()
        composeView = null
        overlayLifecycleOwner = null
        super.onDestroy()
    }

    private fun showBubble() {
        val owner = OverlayLifecycleOwner().apply { attach() }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                VocalRemoverTheme {
                    BubbleOverlayContent(
                        state = state,
                        suggestions = suggestions,
                        onDragDelta = ::moveBubble,
                        onPackagePicked = ::onAppSelected,
                        onStartRecording = ::requestMediaProjectionConsent,
                        onStopRecording = ::requestCaptureStop,
                    )
                }
            }
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        windowManager.addView(view, layoutParams)
        overlayLifecycleOwner = owner
        composeView = view
    }

    private fun moveBubble(deltaX: Float, deltaY: Float) {
        val view = composeView ?: return
        layoutParams.x += deltaX.toInt()
        layoutParams.y += deltaY.toInt()
        windowManager.updateViewLayout(view, layoutParams)
    }

    private fun currentSuggestions(): List<String> =
        CapturePackageSuggestions.orderedDisplayTexts(
            packageDirectory.readRecentPackages(),
            packageDirectory.candidateDisplayNames()
        )

    private fun onAppSelected(packageName: String) {
        val targetUid = packageDirectory.resolveTargetUid(packageName)
        if (targetUid == null) {
            onCaptureFailed("App sorgente non trovata: $packageName")
            return
        }
        packageDirectory.rememberPackage(packageName)
        transition { BubbleOverlayTransitions.selectApp(it, packageName, targetUid) }
        launchTargetApp(packageName)
    }

    private fun launchTargetApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
    }

    private fun requestMediaProjectionConsent() {
        val packageName = state.packageName ?: return
        val targetUid = state.targetUid ?: return
        transition { BubbleOverlayTransitions.requestConsent(it) }
        startActivity(MediaProjectionConsentActivity.createIntent(this, packageName, targetUid))
    }

    private fun requestCaptureStop() {
        transition { BubbleOverlayTransitions.requestStop(it) }
        startService(
            Intent(this, SystemAudioCaptureService::class.java)
                .setAction(SystemAudioCaptureService.ACTION_STOP)
        )
    }

    private fun onCaptureStopped() {
        transition { BubbleOverlayTransitions.stopped(it) }
        startActivity(
            Intent(this, LauncherMainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        stopSelf()
    }

    private fun onCaptureFailed(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        resultStore.claim()?.let { claimedResult ->
            if (claimedResult.kind != CaptureTerminalKind.STOPPED) {
                resultStore.clearClaim(claimedResult.id)
            }
        }
        transition { BubbleOverlayTransitions.failed(it, message) }
        mainHandler.postDelayed({
            transition { BubbleOverlayTransitions.dismissError(it) }
        }, ERROR_DISPLAY_MS)
    }

    private fun transition(update: (BubbleOverlayState) -> BubbleOverlayState?) {
        val next = update(state) ?: return
        state = next
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Widget di registrazione",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val closeIntent = PendingIntent.getService(
            this,
            CLOSE_REQUEST_CODE,
            Intent(this, BubbleOverlayService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Widget di registrazione attivo")
            .setContentText("Tocca il bubble sullo schermo per gestire la registrazione")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Chiudi", closeIntent)
            .build()
    }
}
```

- [ ] **Step 2: Aggiungere permessi e dichiarazione del servizio nel manifest di `:ui`**

In `ui/src/main/AndroidManifest.xml`, aggiungere accanto agli altri `<uses-permission>` esistenti:

```xml
    <!-- Widget flottante per gestire la registrazione sopra altre app -->
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

E dentro `<application>`, dopo la dichiarazione di `com.stef.vocalremover.ui.overlay.MediaProjectionConsentActivity`:

```xml
        <service
            android:name="com.stef.vocalremover.ui.overlay.BubbleOverlayService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="floating_recording_control_widget" />
        </service>
```

- [ ] **Step 3: Verificare che il modulo compili**

Run: `gradle :ui:compileCpuDebugKotlin :ui:lintCpuDebug`
Expected: BUILD SUCCESSFUL, nessun nuovo errore di lint sui nuovi permessi/servizio

- [ ] **Step 4: Verifica manuale su device (Android 10+)**

Installare l'app (`gradle :ui:installCpuDebug`), concedere manualmente il permesso overlay da Impostazioni (`adb shell appops set <applicationId> SYSTEM_ALERT_WINDOW allow` oppure dalla UI di sistema), avviare `BubbleOverlayService` con:

```bash
adb shell am start-foreground-service -n <applicationId>/com.stef.vocalremover.ui.overlay.BubbleOverlayService
```

Verificare che appaia il bubble collassato, che si possa trascinare, che toccandolo si espanda mostrando la lista app, che selezionare un'app dalla lista apra quell'app e ricollassi il bubble, che "Avvia registrazione" apra il dialog di consenso di sistema, che dopo il consenso il bubble mostri "Ferma e elabora", e che fermandolo VocalRemoverApp torni in primo piano con l'elaborazione in corso.

- [ ] **Step 5: Commit**

```bash
git add ui/src/main/java/com/stef/vocalremover/ui/overlay/BubbleOverlayService.kt \
        ui/src/main/AndroidManifest.xml
git commit -m "feat(ui-overlay): add BubbleOverlayService orchestrating the floating widget"
```


---

### Task 8: Collegare `ImportSongScreen` al widget flottante

**Files:**
- Modify: `ui/src/main/java/com/stef/vocalremover/ui/ImportSongScreen.kt`
- Modify: `ui/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `BubbleOverlayService.canShowOverlay(context): Boolean`, `BubbleOverlayService.overlayPermissionSettingsIntent(context): Intent` (Task 7).

- [ ] **Step 1: Aggiungere la stringa per il permesso overlay**

In `ui/src/main/res/values/strings.xml`, accanto a `import_capture_start`:

```xml
    <string name="import_overlay_permission_required">Concedi il permesso di sovrapposizione per usare il widget di registrazione, poi riprova.</string>
```

- [ ] **Step 2: Collegare `CaptureCard` al servizio in `:ui`**

In `ImportSongScreen.kt`, aggiungere gli import:

```kotlin
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.stef.vocalremover.ui.overlay.BubbleOverlayService
```

Nel corpo di `ImportSongScreen`, sostituire:

```kotlin
    var options by rememberSaveable(stateSaver = ImportOptions.Saver) { mutableStateOf(ImportOptions()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val previewMessage = stringResource(R.string.import_preview_not_connected)
    val showPreviewMessage: () -> Unit = {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(previewMessage)
        }
    }
```

con:

```kotlin
    var options by rememberSaveable(stateSaver = ImportOptions.Saver) { mutableStateOf(ImportOptions()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val previewMessage = stringResource(R.string.import_preview_not_connected)
    val overlayPermissionMessage = stringResource(R.string.import_overlay_permission_required)
    val showPreviewMessage: () -> Unit = {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(previewMessage)
        }
    }
    val startCaptureWidget: () -> Unit = {
        if (BubbleOverlayService.canShowOverlay(context)) {
            ContextCompat.startForegroundService(context, Intent(context, BubbleOverlayService::class.java))
        } else {
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(overlayPermissionMessage)
            }
            context.startActivity(BubbleOverlayService.overlayPermissionSettingsIntent(context))
        }
    }
```

E sostituire la riga `CaptureCard(onStart = showPreviewMessage)` con:

```kotlin
            CaptureCard(onStart = startCaptureWidget)
```

- [ ] **Step 3: Verificare che il modulo compili**

Run: `gradle :ui:compileCpuDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Verifica manuale su device**

Installare l'app (`gradle :ui:installCpuDebug`), aprire "Importa canzone", toccare "Avvia" nella card di registrazione:
- se il permesso overlay non è concesso, deve aprirsi la schermata di sistema per concederlo (con lo snackbar esplicativo mostrato prima);
- una volta concesso e ritoccato "Avvia", deve apparire il bubble flottante sopra l'app.

- [ ] **Step 5: Commit**

```bash
git add ui/src/main/java/com/stef/vocalremover/ui/ImportSongScreen.kt \
        ui/src/main/res/values/strings.xml
git commit -m "feat(ui): wire ImportSongScreen capture card to the floating bubble widget"
```


---

### Task 9: Checklist di verifica manuale end-to-end

**Files:** nessuno (solo verifica)

**Interfaces:** nessuna nuova — verifica l'integrazione di tutti i task precedenti.

- [ ] **Step 1: Eseguire l'intera suite di test JVM e il lint, come baseline finale**

Run: `gradle :engine:testCpuDebugUnitTest :engine:testWebgpuDebugUnitTest :engine:lintCpuDebug :ui:lintCpuDebug`
Expected: BUILD SUCCESSFUL, tutti i test in PASS

- [ ] **Step 2: Checklist manuale completa su device Android 10+ reale**

Eseguire nell'ordine, segnando ogni punto:

1. Da "Importa canzone", toccare "Avvia" nella card di registrazione con permesso overlay non ancora concesso → si apre la schermata di sistema, si concede il permesso, si torna all'app.
2. Toccare di nuovo "Avvia" → appare il bubble flottante collassato sopra l'app.
3. Trascinare il bubble in un punto diverso dello schermo → resta nella nuova posizione.
4. Toccare il bubble (senza trascinare) → si espande mostrando la lista app e il pulsante "Avvia registrazione".
5. Toccare una voce della lista (es. YouTube) → quell'app si apre in primo piano, il bubble si ricollassa.
6. Far partire una riproduzione audio nell'app aperta.
7. Riaprire il bubble (tap) → premere "Avvia registrazione" → appare il dialog di consenso di sistema → confermare.
8. Il bubble mostra "Ferma e elabora" e lo stato "Registrazione in corso…"; la riproduzione nell'altra app continua normalmente.
9. Dopo alcuni secondi, toccare "Ferma e elabora" → il bubble sparisce e VocalRemoverApp torna in primo piano mostrando l'elaborazione in corso.
10. A elaborazione completata, verificare che il brano strumentale sia riproducibile dalla UI esistente.
11. Ripetere i punti 2–5 e, questa volta, negare il consenso MediaProjection al passo 7 → il bubble torna allo stato iniziale con un messaggio di errore, senza crash, e resta disponibile per un nuovo tentativo.
12. Forzare una sorgente con cattura bloccata (se disponibile, es. un'app con `ALLOW_CAPTURE_BY_NONE`) → il bubble mostra il messaggio di blocco e torna allo stato iniziale senza crash.
13. Toccare "Chiudi" sulla notifica del widget mentre il bubble è inattivo (fase IDLE) → il bubble e la sua notifica scompaiono.

Expected: tutti i 13 punti si comportano come descritto, senza crash né stati bloccati.

- [ ] **Step 3: Annotare eventuali problemi trovati e risolverli prima di considerare il lavoro completo**

Se un punto della checklist fallisce, tornare al task corrispondente (vedi File Structure) e correggere prima di procedere.
