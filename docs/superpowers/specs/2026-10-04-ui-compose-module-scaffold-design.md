# Modulo `:ui` Compose — punto di accesso dell'app

## Contesto

L'app era un unico modulo Gradle (`:app`) con layout XML (`activity_main.xml`) e
`ViewBinding`. L'obiettivo di lungo termine è spostare la presentazione in
Jetpack Compose, in un modulo dedicato che diventa anche il punto di accesso
dell'applicazione.

Questa spec copre il primo passo: ristrutturare i moduli e introdurre una
`MainActivity` Compose come launcher, senza migrare ancora la logica della
schermata esistente. La migrazione vera e propria (schermata principale,
`ViewModel`, uso di `AudioPlayer` / `VocalRemover` / `capture/*` da Compose) e
la rimozione della vecchia `MainActivity` saranno oggetto di spec e piani
separati.

## Decisioni architetturali

- `:ui` è il modulo **application**: produce gli APK e contiene launcher,
  application ID, packaging e benchmark `androidTest`.
- `:app` è rinominato **`:engine`** ed è una **library** Android con decodifica,
  inferenza ONNX, cattura, asset del modello, test JVM e la vecchia
  `MainActivity` XML.
- Dipendenza a senso unico: `:ui` → `:engine`. `:engine` non dipende mai da `:ui`.
- I flavor `cpu`/`webgpu`/`qnn` (dimensione `backend`) esistono in entrambi i
  moduli con gli stessi nomi:
  - in `:engine` selezionano la dipendenza ONNX Runtime e
    `BuildConfig.EXECUTION_BACKEND`;
  - in `:ui` definiscono `applicationIdSuffix`, quindi gli application ID
    restano `com.example.vocalremover.{cpu,webgpu,qnn}`, con installazioni e
    cache dei modelli invariate.
- Le classi in `capture/*` restano `internal` a `:engine`. Ciò che servirà alla
  UI Compose andrà esposto con API pubbliche definite caso per caso durante la
  migrazione.

## Scope

1. Spostamento di `app/` in `engine/` con plugin `com.android.library`:
   - rimossi `applicationId`, `targetSdk`, versioni, `applicationIdSuffix`,
     signing di release e packaging JNI;
   - rimosso il plugin `google-services`.
2. `ui/build.gradle.kts` come application:
   - plugin `com.android.application`, `kotlin.android`, `kotlin.plugin.compose`,
     `google-services`;
   - `namespace = "com.example.vocalremover.ui"`,
     `applicationId = "com.example.vocalremover"`;
   - flavor con suffissi, `useLegacyPackaging` per qnn, `pickFirsts` per
     `libc++_shared.so`;
   - dipendenza `implementation(project(":engine"))` e dipendenze Compose
     (BOM, ui, ui-tooling(-preview), material3, activity-compose,
     lifecycle-viewmodel-compose).
3. `compileSdk = 37` in entrambi i moduli (`targetSdk` resta 36): è richiesto da
   lifecycle-compose 2.11.0 (allineato alla lifecycle 2.11.0 già in uso) e da
   Compose BOM 2026.08.00 e successivi.
4. Nuova `com.stef.vocalremover.ui.MainActivity` (`ComponentActivity` +
   `setContent`), launcher dichiarato in `ui/src/main/AndroidManifest.xml`.
   Mostra una schermata minima con un pulsante che apre la vecchia
   `com.example.vocalremover.MainActivity`.
5. La vecchia `MainActivity` perde l'intent-filter `MAIN`/`LAUNCHER` e mantiene
   l'intent-filter `VIEW audio/*`. Gli attributi `<application>` (label, tema,
   `largeHeap`, `allowBackup`) passano al manifest di `:ui`.
6. Spostamenti:
   - `google-services.json` va in `ui/`;
   - `BackendBenchmarkTest` va in `ui/src/androidTest`, che compila anche
     `engine/src/benchmarkShared/java`;
   - i test JVM restano in `:engine`.
7. Aggiornati `README.md`, `.github/copilot-instructions.md`,
   `scripts/run-testlab.sh` e `convert_fp16.py` con i nuovi percorsi e task
   (`:ui:assemble…`, `:engine:test…`, APK `ui-<flavor>-debug.apk`).

## Fuori scope

- Migrazione della logica di `MainActivity` in Compose, `ViewModel`, tema
  Material3 dedicato.
- Rimozione della vecchia `MainActivity`, di `activity_main.xml` e di ViewBinding.

## Verifica

- `:ui:assembleCpuDebug`, `:ui:assembleWebgpuDebug` e `:ui:assembleQnnDebug`
  compilano. I manifest uniti hanno il launcher sulla nuova Activity e
  `uses-native-library libcdsprpc.so` e `extractNativeLibs="true"` solo in qnn.
  Gli APK contengono il modello e le librerie ORT/QNN attese.
- `:ui:assemble{Cpu,Qnn}DebugAndroidTest` compilano.
- `:engine:testCpuDebugUnitTest` passa.
