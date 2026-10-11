# Schermata «Importa canzone» (prima versione grafica)

## Obiettivo
Nuova schermata Compose in `:ui` che riproduce lo schema concordato, solo grafica interattiva:
nessuna elaborazione reale. Le azioni non ancora collegate al motore sono segnalate come anteprima.

## Struttura
- `ImportSongScreen.kt`: composable `ImportSongScreen(onBack)` con
  - barra superiore con freccia ← e titolo «Importa canzone»;
  - card importazione: icona 📁, «Seleziona un file» (niente trascinamento in questa versione),
    pulsante «Sfoglia memoria», formati supportati MP3, WAV, FLAC, M4A;
  - sezione «O registra da altre app»: card «Registra audio interno» con descrizione e pulsante «Avvia»;
  - divisore e sezione «Opzioni elaborazione»: qualità a scelta singola (Veloce / Bilanciata / Studio),
    checkbox «Salva automaticamente in memoria» (✔), «Normalizza volume» (✔),
    «Aggiungi leggero riverbero» (☐);
  - pulsante principale «✨ Elabora ora».
- Stato delle opzioni in `ImportOptions` (con enum `ProcessingQuality`), conservato con `rememberSaveable`.
- Pagina scorrevole verticalmente.

## Navigazione
`MainActivity` usa uno stato salvabile per alternare Player e Importa. Il player ha un pulsante
«Importa canzone»; la freccia ← e il tasto Indietro di sistema (`BackHandler`) riportano al player.

## Anteprima
«Sfoglia memoria», «Avvia» ed «Elabora ora» mostrano uno snackbar: la funzione non è ancora collegata.
Qualità e riverbero non esistono ancora nel motore.

## Testi
Tutti i testi visibili in `ui/src/main/res/values/strings.xml`, letti con `stringResource`.

## Verifica
`gradle :ui:assembleCpuDebug :ui:lintCpuDebug`.
