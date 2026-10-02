# SKLEJKA

Prosta, całkowicie offline aplikacja Android do łączenia zdjęć w jeden obraz.
Kotlin, Jetpack Compose, Material 3. Jeden moduł `:app`. Android 8.0+ (API 26).

## Używanie

1. **DODAJ ZDJĘCIA** — wybierz lokalne pliki, również kilka jednocześnie.
   Możesz też zaznaczyć zdjęcia w Galerii: **Udostępnij → Sklejka**.
2. Ustaw kolejność: przytrzymaj uchwyt **≡** i przeciągnij. Strzałki ↑ / ↓
   pozwalają zmienić kolejność bez gestu. Przycisk **Usuń** usuwa pojedynczy wpis.
   Kolejne dodanie dopisuje obrazy do listy; **WYCZYŚĆ** usuwa całą listę.
3. Wybierz **PION**, **POZIOM** lub **SIATKA**. Dla siatki wybierz **2 / 3 / 4**
   kolumny (domyślnie 3). Wymiary wyniku aktualizują się po zmianie ustawień.
   **ODWRÓĆ KOLEJNOŚĆ** odwraca widoczną listę;
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
- W pionie i poziomie mniejsze zdjęcie jest centrowane. Przy nieparzystej różnicy dodatkowy piksel
  tła przypada na prawą / dolną stronę.
- Siatka: kolejne zdjęcia z listy trafiają od lewej do prawej, potem do następnego
  wiersza. Każda kolumna ma szerokość najszerszego zdjęcia z całej listy.
  Zdjęcia zachowują natywne wymiary i są wyrównane do lewego górnego rogu komórki;
  nie są powiększane ani pomniejszane. Każdy wiersz ma wysokość najwyższego
  zdjęcia w tym wierszu. Odstęp obowiązuje między kolumnami i wierszami.
  Niepełny ostatni wiersz zaczyna się z lewej; puste komórki i przestrzeń wokół
  mniejszych zdjęć zachowują wybrane tło. Wybrane kolumny pozostają zarezerwowane,
  również gdy zdjęć jest mniej niż kolumn.
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
./gradlew build
```

Windows:

```powershell
.\gradlew.bat build
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Workflow GitHub Actions wykonuje te same zadania i udostępnia APK jako artefakt
`sklejka-debug` (ZIP zawierający `sklejka-debug.apk`) oraz raporty testów i lint.

## Testy

Testy JVM obejmują pion, poziom, różne rozmiary, centrowanie, oba odwrócenia,
przezroczystość, pozycje pikseli, odstępy, wyliczanie wymiarów, osiem orientacji,
duży układ 1440 × 20000, limity pamięci, przepełnienie i anulowanie.

Testy Robolectric z natywną grafiką Androida obejmują dekodowanie fragmentów
PNG z alfą, orientacje, rzeczywisty JPEG z EXIF, pełny eksport PNG/JPG,
brak INTERNET w manifeście, filtry udostępniania oraz rzeczywiste uruchomienie
ekranu przez ACTION_SEND / ACTION_SEND_MULTIPLE z zachowaniem kolejności i powtórzeń.

Testy siatki obejmują 4 zdjęcia / 2 kolumny, 6 zdjęć / 3 kolumny, niepełny
ostatni wiersz, odstępy w obu osiach, różne proporcje, zachowanie wszystkich
pikseli, kolejność i odwrócenie, przezroczystość, limity buforów oraz przepełnienie
wymiarów. Pełne eksporty sprawdzają PNG z alfą, JPG z jakością 80 / 91 / 100
(porównanie z niezależnie przygotowanym płótnem), EXIF i cztery screenshoty
1440 × 5000 w siatce 2 × 2 z odstępem 4 px → PNG 2884 × 10004.
Test modelu sprawdza zmianę i przywrócenie liczby kolumn oraz obecne operacje
zmiany kolejności, odwrócenia, usuwania i czyszczenia.

Aktualne wyniki builda, wszystkich testów i lint są dostępne w GitHub Actions;
artefakt `test-reports` zawiera raporty HTML.

Przed użyciem na konkretnym telefonie sprawdź operacyjnie: import z Galerii,
przeciąganie, eksport i otwieranie wyniku. Testy automatyczne nie zastępują
meldunku z instalacji na tym urządzeniu.
