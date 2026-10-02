# SKLEJKA

Prosta, całkowicie offline aplikacja Android do łączenia zdjęć w jeden obraz.
Kotlin, Jetpack Compose, Material 3. Jeden moduł `:app`. Android 8.0+ (API 26).

## Używanie

1. **DODAJ ZDJĘCIA** — wybierz lokalne pliki, również kilka jednocześnie.
   Możesz też zaznaczyć zdjęcia w Galerii: **Udostępnij → Sklejka**.
2. Ustaw kolejność: przytrzymaj uchwyt **≡** i przeciągnij. Strzałki ↑ / ↓
   pozwalają zmienić kolejność bez gestu. Przycisk **Usuń** usuwa pojedynczy wpis.
   Kolejne dodanie dopisuje obrazy do listy; **WYCZYŚĆ** usuwa całą listę.
3. Wybierz **PION** lub **POZIOM**. **ODWRÓĆ KOLEJNOŚĆ** odwraca widoczną listę;
   eksport zawsze zachowuje kolejność wyświetloną na ekranie.
4. Wybierz PNG / JPG i tło: białe, czarne lub przezroczyste (PNG).
   Domyślnie: pion, PNG, białe tło i 0 px odstępu.
   Odstęp: 0–50 px. Jakość JPG: 80–100, domyślnie 100.
5. **SKLEJ I ZAPISZ**. Wynik pokaże podgląd, wymiary, rozmiar i lokalizację.
   Można go otworzyć, udostępnić lub zapisać kopię w wybranym folderze.

Android 10+ zapisuje wynik publicznie przez MediaStore do `Pictures/Sklejka`.
Na Androidzie 8–9 systemowy dialog Storage Access Framework pozwala wybrać
lokalizację. Aplikacja nie potrzebuje szerokiego dostępu do magazynu telefonu.

## Reguły obrazu

- Pion: szerokość = największa szerokość; wysokość = suma wysokości + odstępy.
- Poziom: szerokość = suma szerokości + odstępy; wysokość = największa wysokość.
- Mniejsze zdjęcie jest centrowane. Przy nieparzystej różnicy dodatkowy piksel
  tła przypada na prawą / dolną stronę.
- Wymiary są liczone po zastosowaniu orientacji EXIF.
- Brak przycinania, resamplingu, dopasowywania panoram i zmniejszania eksportu.
- Wszystkie osiem orientacji EXIF to dokładne przestawienie pikseli.
- PNG zapisuje bezstratnie zdekodowane kanały RGBA8 w sRGB. Przezroczyste tło
  zachowuje kanał alfa; tło nieprzezroczyste komponuje z nim piksele półprzezroczyste.
  PNG nie oznacza bajtowej identyczności plików wejściowych ani zachowania
  źródłowych metadanych, HDR lub 16-bitowej głębi kanału.
- JPEG jest ponownie kodowany, nawet przy jakości 100; nie jest bit-perfect
  względem źródłowych plików JPEG. Eksport JPG nie zmienia wymiarów.
- Wyłącznie miniatury i podgląd są pomniejszane na potrzeby ekranu.
- Powtórzone zdjęcia pozostają na liście — brak deduplikacji.

## Pamięć

PNG jest kodowany strumieniowo: małe pasy obrazu, jeden aktywny dekoder źródła,
jedna linia PNG oraz bufor kompresji. Nie powstaje pełna bitmapa wyniku.
Pas wyniku zajmuje najwyżej 2 MiB; jednocześnie potrzebne są również bufory
fragmentu źródła i pamięć natywnego dekodera. Limit szerokości PNG: 131072 px.

JPG używa systemowego kodera bitmapy. Przed alokacją aplikacja oblicza rozmiar
wyniku i zapotrzebowanie `szerokość × wysokość × 4 + 24 MiB`. Zapis jest
odrzucany, gdy przekracza 60% aktualnie dostępnej pamięci sterty lub wymiar
65500 px. Komunikat podaje przewidywane wymiary i proponuje PNG albo mniejszą
liczbę zdjęć. Brak automatycznej degradacji jakości.

Formaty bez obsługi odczytu fragmentów mają ograniczony pełny odczyt źródła:
do 32 MiB i do 25% dostępnej sterty. Nieobsługiwane / niedostępne / zbyt duże
pliki powodują komunikat. Błąd, brak miejsca i anulowanie usuwają niepełny
plik wynikowy. Eksport odbywa się poza wątkiem interfejsu.

## Prywatność

Brak uprawnienia `INTERNET`, sieci, backendu, kont, reklam, analityki, telemetryki,
WebView, AI, OCR i OpenCV. Kopia zapasowa aplikacji jest wyłączona.
Systemowy wybór pliku jest ograniczony do lokalnych zasobów.
Udostępnianie wyniku następuje tylko po naciśnięciu **UDOSTĘPNIJ**.

## Budowanie

Wymagania: JDK 17, Android SDK 35, Build Tools 35.0.0.
Gradle Wrapper 8.11.1 (z weryfikacją SHA-256), AGP 8.9.2, Kotlin 2.1.20.
Pobranie narzędzi i zależności przy pierwszym buildzie wymaga połączenia;
zainstalowana aplikacja działa offline.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Workflow GitHub Actions wykonuje te same zadania i udostępnia APK jako artefakt
`sklejka-debug` oraz raporty testów.

## Testy

Testy JVM obejmują pion, poziom, różne rozmiary, centrowanie, oba odwrócenia,
przezroczystość, pozycje pikseli, odstępy, wyliczanie wymiarów, osiem orientacji,
duży układ 1440 × 20000, limity pamięci, przepełnienie i anulowanie.

Testy Robolectric z natywną grafiką Androida obejmują dekodowanie fragmentów
PNG z alfą, orientacje, rzeczywisty JPEG z EXIF, pełny eksport PNG/JPG,
brak INTERNET w manifeście, filtry udostępniania oraz rzeczywiste uruchomienie
ekranu przez ACTION_SEND / ACTION_SEND_MULTIPLE z zachowaniem kolejności i powtórzeń.

Zweryfikowano 2 października 2026: Gradle Wrapper wykonał
`testDebugUnitTest lintDebug assembleDebug` — **BUILD SUCCESSFUL, 25/25 testów**.
Test dużego eksportu utworzył rzeczywisty PNG 1440 × 20000 z czterech wejść
1440 × 5000, bez pełnej bitmapy wyniku. Lint: 0 błędów, 2 ostrzeżenia
(DataExtractionRules, UseKtx). Podpis debug APK i wyrównanie ZIP 16 KiB są poprawne.

Przed użyciem na konkretnym telefonie sprawdź operacyjnie: import z Galerii,
przeciąganie, eksport i otwieranie wyniku. Testy automatyczne nie zastępują
meldunku z instalacji na tym urządzeniu.
